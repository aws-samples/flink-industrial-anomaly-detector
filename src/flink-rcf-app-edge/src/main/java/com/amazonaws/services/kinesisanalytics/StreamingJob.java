/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.kinesisanalytics;

import com.amazon.randomcutforest.RandomCutForest;
import com.amazon.randomcutforest.runner.LineTransformer;

import com.amazonaws.services.kinesisanalytics.operators.AnomalyDetector;
import com.amazonaws.services.kinesisanalytics.operators.AnomalyJsonToStringPayloadFn;
import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import com.amazonaws.services.kinesisanalytics.utils.ParameterToolUtils;
import com.amazonaws.services.streammanager.StreamManagerSink;
import com.amazonaws.services.streammanager.StreamManagerSource;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.LocalStreamEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class StreamingJob extends AnomalyDetector {

	private static final Logger logger = LoggerFactory.getLogger(StreamingJob.class);
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

	

	public static void main(String[] args) throws Exception {
		// set up the execution environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);
		env.setRestartStrategy(RestartStrategies.fixedDelayRestart(
				  3, // number of restart attempts
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
		
		
		params.add(nl+"region: "+parameter.get("region")+nl);
		params.add("ggStreamHost: "+parameter.get("ggStreamHost")+nl);
		params.add("ggStreamPort: "+parameter.get("ggStreamPort")+nl);
		params.add("ggSourceStreamName: "+parameter.get("ggSourceStreamName")+nl);
		params.add("ggTargetStreamName: "+parameter.get("ggTargetStreamName")+nl);
		params.add("ggStreamBatchSize: "+parameter.get("ggStreamBatchSize")+nl);
		params.add("ggStreamExportKinesis: "+parameter.get("ggStreamExportKinesis")+nl);
		
		
		params.add("RcfShingleSize: "+parameter.get("RcfShingleSize")+nl);
		params.add("RcfShingleCyclic: "+parameter.get("RcfShingleCyclic")+nl);
		params.add("RcfNumberOfTrees: "+parameter.get("RcfNumberOfTrees")+nl);
		params.add("RcfSampleSize: "+parameter.get("RcfSampleSize")+nl);
		params.add("RcfLambda: "+parameter.get("RcfLambda")+nl);
		params.add("RcfRandomSeed: "+parameter.get("RcfRandomSeed")+nl);
		
		logger.info("APPLICATION PARAMETERS: "+nl+params);

	    
		String region = parameter.get("region", "eu-central-1").toString();
		String ggStreamHost = parameter.get("ggStreamHost", "gate0.local").toString();
		String ggStreamPort = parameter.get("ggStreamPort", "8089").toString();
		String ggSourceStreamName = parameter.get("ggSourceStreamName", "TesimSourceStream").toString();
		String ggTargetStreamName = parameter.get("ggTargetStreamName", "GGTargetStream").toString();
		String ggStreamExportKinesis = parameter.get("ggStreamExportKinesis", "tep-ingest-greengrass").toString();
		int ggStreamBatchSize = Integer.parseInt(parameter.get("ggStreamBatchSize", "100"));

		DataStream<String> tesimStream = env.addSource(new StreamManagerSource(ggSourceStreamName, ggStreamHost, ggStreamPort))
				.name("StreamManagerSourceStream")
				.map(new AnomalyJsonToStringPayloadFn(parameter))
				.name("AnomalyScoreFromTesimSource");

		// this `sink` will define the ingestion to the target StreamManager
		tesimStream.addSink(new StreamManagerSink(region, ggTargetStreamName, ggStreamHost, ggStreamPort, ggStreamExportKinesis, ggStreamBatchSize)).name("StreamManagerSink");
		
		
		// get execution plan
		logger.info(env.getExecutionPlan());
		
		// execute flink program
		env.execute("Amazon Timestream Flink Anomaly Detection Sink");

	}

}
