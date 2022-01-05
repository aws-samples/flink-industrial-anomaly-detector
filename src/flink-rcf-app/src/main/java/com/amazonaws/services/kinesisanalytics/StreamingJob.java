/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.kinesisanalytics;

import com.amazon.randomcutforest.RandomCutForest;
import com.amazon.randomcutforest.runner.LineTransformer;
import com.amazonaws.services.kinesisanalytics.flink.connectors.producer.FlinkKinesisFirehoseProducer;
import com.amazonaws.services.kinesisanalytics.operators.AnomalyDetector;
import com.amazonaws.services.kinesisanalytics.operators.AnomalyJsonToTimestreamPayloadFn;
import com.amazonaws.services.kinesisanalytics.operators.CountMeasuresWindowFunction;
import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.amazonaws.services.kinesisanalytics.utils.ParameterToolUtils;
import com.amazonaws.services.timestream.TimestreamSink;
import com.amazonaws.services.timestream.TimestreamInitializer;
import com.amazonaws.services.timestream.TimestreamPoint;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvSchema.Builder;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class StreamingJob extends AnomalyDetector {

	public StreamingJob(ParameterTool parameter) {
		super(parameter, AnomalyScoreTransformer::new);
	}

	public static class AnomalyScoreTransformer implements LineTransformer {
		private final RandomCutForest forest;

		public AnomalyScoreTransformer(RandomCutForest forest) {
			this.forest = forest;
		}

		@Override
		public List<String> getResultValues(double... point) {
			double score = forest.getAnomalyScore(point);
			forest.update(point);
			return Collections.singletonList(Double.toString(score));
		}

		@Override
		public List<String> getEmptyResultValue() {
			return Collections.singletonList("NA");
		}

		@Override
		public List<String> getResultColumnNames() {
			return Collections.singletonList("anomaly_score");
		}

		@Override
		public RandomCutForest getForest() {
			return forest;
		}
	}

	private static final String DEFAULT_STREAM_NAME = "tep-ingest";
	private static final String DEFAULT_REGION_NAME = "eu-central-1";
	private static final String sideOutputDeliveryStreamName = "flinkSideDeliveryStream";

	public static DataStream<String> createKinesisSource(StreamExecutionEnvironment env, ParameterTool parameter)
			throws Exception {

		// set Kinesis consumer properties
		Properties kinesisConsumerConfig = new Properties();
		// set the region the Kinesis stream is located in
		kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_REGION,
				parameter.get("KinesisRegion", DEFAULT_REGION_NAME));
		// obtain credentials through the DefaultCredentialsProviderChain, which
		// includes the instance metadata
		kinesisConsumerConfig.setProperty(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "AUTO");

		String adaptiveReadSettingStr = parameter.get("SHARD_USE_ADAPTIVE_READS", "false");

		if (adaptiveReadSettingStr.equals("true")) {
			kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_USE_ADAPTIVE_READS, "true");
		} else {
			// poll new events from the Kinesis stream once every second
			kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS,
					parameter.get("SHARD_GETRECORDS_INTERVAL_MILLIS", "1000"));
			// max records to get in shot
			kinesisConsumerConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_MAX,
					parameter.get("SHARD_GETRECORDS_MAX", "10000"));
			
			kinesisConsumerConfig.setProperty(ConsumerConfigConstants.STREAM_INITIAL_POSITION, "LATEST");
		}

		// create Kinesis source
		DataStream<String> kinesisStream = env.addSource(new FlinkKinesisConsumer<>(
				// read events from the Kinesis stream passed in as a parameter
				parameter.get("InputStreamName", DEFAULT_STREAM_NAME),
				// deserialize events with EventSchema
				new SimpleStringSchema(),
				// using the previously defined properties
				kinesisConsumerConfig)).name("KinesisSource");

		return kinesisStream;
	}

	private static FlinkKinesisFirehoseProducer<String> createFirehoseSinkFromStaticConfig(ParameterTool parameter) {

		Properties outputProperties = new Properties();
		outputProperties.setProperty(ConsumerConfigConstants.AWS_REGION, DEFAULT_REGION_NAME);

		FlinkKinesisFirehoseProducer<String> sink = new FlinkKinesisFirehoseProducer<>(parameter.get("SideCSVDeliveryStreamName", sideOutputDeliveryStreamName),
				new SimpleStringSchema(), outputProperties);
		return sink;
	}

	@SuppressWarnings({ "serial" })
	public static void main(String[] args) throws Exception {
		
		// set up the execution environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setRestartStrategy(RestartStrategies.fixedDelayRestart(
				  5, // number of restart attempts
				  Time.of(10, TimeUnit.SECONDS) // delay
		));

		// setup parameter handling
		ParameterTool parameter;

	    if (env instanceof LocalStreamEnvironment) {
	      // read parameters from command line
	    	parameter = ParameterToolUtils.fromArgsAndApplicationProperties(args);
	    } else {
	      // read parameters set in KDA
	      Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();

	      Properties flinkProperties = applicationProperties.get("FlinkApplicationProperties");

	      if (flinkProperties == null) {
	        throw new RuntimeException("Unable to load FlinkApplicationProperties properties from the Kinesis Analytics Runtime.");
	      }

	      parameter = ParameterToolUtils.fromApplicationProperties(flinkProperties);
	    }
	    ArrayList<String> params = new ArrayList<String>();
		String nl = "\n";
		
		params.add(nl+"InputStreamName: "+parameter.get("InputStreamName")+nl);
		params.add("SideCSVDeliveryStreamName: "+parameter.get("SideCSVDeliveryStreamName")+nl);
		params.add("TimeStreamRegion: "+parameter.get("TimeStreamRegion")+nl);
		params.add("TimeStreamDbName: "+parameter.get("TimeStreamDbName")+nl);
		params.add("TimeStreamTableName: "+parameter.get("TimeStreamTableName")+nl);
		params.add("TimeStreamIngestBatchSize: "+parameter.get("TimeStreamIngestBatchSize")+nl);
		params.add("KinesisRegion: "+parameter.get("KinesisRegion")+nl);
		params.add("RcfShingleSize: "+parameter.get("RcfShingleSize")+nl);
		params.add("RcfShingleCyclic: "+parameter.get("RcfShingleCyclic")+nl);
		params.add("RcfNumberOfTrees: "+parameter.get("RcfNumberOfTrees")+nl);
		params.add("RcfSampleSize: "+parameter.get("RcfSampleSize")+nl);
		params.add("RcfLambda: "+parameter.get("RcfLambda")+nl);
		params.add("RcfRandomSeed: "+parameter.get("RcfRandomSeed")+nl);
		
		System.out.println("APPLICATION PARAMETERS: "+nl+params);

	    
		String region = parameter.get("TimeStreamRegion", "eu-central-1").toString();
		String databaseName = parameter.get("TimeStreamDbName", "kdaflink").toString();
		String tableName = parameter.get("TimeStreamTableName", "kinesisdata1").toString();
		//String tableName2 = parameter.get("TimeStreamTableName", "kinesisdata2").toString();
		int batchSize = Integer.parseInt(parameter.get("TimeStreamIngestBatchSize", "50"));

		TimestreamInitializer timestreamInitializer = new TimestreamInitializer(region);
		timestreamInitializer.createDatabase(databaseName);
		timestreamInitializer.createTable(databaseName, tableName);
		//timestreamInitializer.createTable(databaseName, tableName2);

		final OutputTag<String> outputTag = new OutputTag<String>("side-output") {};

		DataStream<String> input = createKinesisSource(env, parameter)
				.process(new ProcessFunction<String, String>() {
				@Override
				public void processElement(String value, Context ctx, Collector<String> out) throws Exception {
					// emit data to regular output
					out.collect(value);
					// JSON->CSV
					JsonNode jsonTree = new ObjectMapper().readTree(value);
					Builder csvSchemaBuilder = CsvSchema.builder();
					//JsonNode firstObject = jsonTree.elements().next();
					jsonTree.fieldNames().forEachRemaining(fieldName -> {csvSchemaBuilder.addColumn(fieldName);} );
					CsvSchema csvSchema = csvSchemaBuilder.build();
					CsvMapper csvMapper = new CsvMapper();
					String csvOut = csvMapper.writerFor(JsonNode.class)
					  .with(csvSchema)
					  .writeValueAsString(jsonTree);
					// emit data to side output
					ctx.output(outputTag, csvOut);
				}
			}).name("rawCsvEmitter");

		DataStream<List<TimestreamPoint>> mainStream = createKinesisSource(env, parameter)
				.map(new AnomalyJsonToTimestreamPayloadFn(parameter)).name("MaptoTimestreamPayload");
		
		
		mainStream.countWindowAll(50, 50).process(new CountMeasuresWindowFunction()) .name("CountMeasuresWindowFunction");

		/*
		 * Experimental DTW pattern finder routines...
		 * 
		 * DataStream<List<TimestreamPoint>> patternFinderStream = mainStream
		 * .countWindowAll(166, 20)
		 * //.windowAll(SlidingEventTimeWindows.of(Time.seconds(35), Time.seconds(10)))
		 * 
		 * .process(new DtwProcessWindowFunction()) .name("DtwProcessWindowFunction");
		 */
		

		// this `sink` will define the ingestion to timestream
		mainStream.addSink(new TimestreamSink(region, databaseName, tableName, batchSize)).name("TimeStreamSink");
		
		/*
		 * Experimental DTW pattern finder routines
		 * 
		 * patternFinderStream.addSink(new TimestreamSink(region, databaseName, tableName2, batchSize)).name("TimeStreamSink2");
		 */

		// this side-`sink` will forward data to an existing firehose delivery stream
		// (->S3)
		DataStream<String> sideStream = ((SingleOutputStreamOperator<String>) input).getSideOutput(outputTag);
		sideStream.addSink(createFirehoseSinkFromStaticConfig(parameter)).name("FireHoseSink");

		System.out.println(env.getExecutionPlan());
		
		// execute program
		env.execute("Amazon Timestream Flink Anomaly Detection Sink");

	}

}
