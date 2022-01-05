/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.kinesisanalytics.operators;


import com.amazonaws.services.kinesisanalytics.StreamingJob;
import com.amazonaws.services.timestream.TimestreamPoint;
import com.amazonaws.services.timestreamwrite.model.MeasureValueType;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SuppressWarnings("serial")
public class AnomalyJsonToTimestreamPayloadFn extends RichMapFunction<String, List<TimestreamPoint>> {
	protected final ParameterTool parameter;
	private final Logger logger = LoggerFactory.getLogger(getClass());
	
	public AnomalyJsonToTimestreamPayloadFn(ParameterTool parameter) {
    	this.parameter = parameter;
    }

	// create new instance of StreamingJob for running our Forest
	StreamingJob overallAnomalyRunner1;
	StreamingJob overallAnomalyRunner2;
	StreamingJob overallAnomalyRunner3;
	
	// use `open`method as RCF initialization
	@Override
	public void open(Configuration parameters) throws Exception {
		overallAnomalyRunner1 = new StreamingJob(parameter);
		overallAnomalyRunner2 = new StreamingJob(parameter);
		overallAnomalyRunner3 = new StreamingJob(parameter);
		super.open(parameters);
	}

	private static enum measureTypes {
		CONTINUOUS, 
		SAMPLED_REACTOR_FEED_STREAM6, 
		SAMPLED_PURGE_GAS_ANALYSIS_STREAM9, 
		SAMPLED_PRODUCT_ANALYSIS_STREAM11,
		CONTINUOUS_REACTOR_FEED_STREAM6,
		CONTINUOUS_PURGE_GAS_STREAM9,
		CONTINUOUS_PRODUCT_ANALYSIS_STREAM11
		;
	}

	public static ArrayList<String> tepMeasureVariables(measureTypes val) {
		
		//		# Continuous process measurerments XMEAS 1-22
		//						sampling frequency: 3min
			
		//		# Sampled Process Measurements
		//				*-->Reactor Feed Analysis (Stream 6) XMEAS 23-28
		//						Sampling Frequency = 0.1 hr (6min)
		//						Dead Time = 0.1 hr
		//						Units in Mol %
						
		//				*-->Purge Gas Analysis (Stream 9) XMEAS 29-36
		//						Sampling Frequency = 0.1 hr (6min)
		//						Dead Time = 0.1 hr
		//						Units in Mol %
						
		//				*--> Product Analysis (Stream 11) XMEAS 37-41
		//						Sampling Frequency = 0.25 hr (15min)
		//						Dead Time = 0.25 hr
		//						Units in Mol %

		ArrayList<String> cont_process_measurements = new ArrayList<String>();
		ArrayList<String> reactor_feed_analysis_stream6 = new ArrayList<String>();
		ArrayList<String> purge_gas_analysis_stream9 = new ArrayList<String>();
		ArrayList<String> product_analysis_stream11 = new ArrayList<String>();
		ArrayList<String> cont_reactor_feed_analysis_stream6 = new ArrayList<String>();
		ArrayList<String> cont_purge_gas_analysis_stream9 = new ArrayList<String>();
		ArrayList<String> cont_product_analysis_stream11 = new ArrayList<String>();
		for (int i = 1; i <= 41; i++) {
			if (i <= 22)
				cont_process_measurements.add("xmeas_" + i);
			if (i >= 23 && i <= 28)
				reactor_feed_analysis_stream6.add("xmeas_" + i);
			if (i >= 29 && i <= 36)
				purge_gas_analysis_stream9.add("xmeas_" + i);
			if (i >= 37 && i <= 41)
				product_analysis_stream11.add("xmeas_" + i);
			if (i >= 6 && i <= 9)
				cont_reactor_feed_analysis_stream6.add("xmeas_" + i);
			if (i >= 10 && i <= 13)
				cont_purge_gas_analysis_stream9.add("xmeas_" + i);
			if (i >= 15 && i <= 19)
				cont_product_analysis_stream11.add("xmeas_" + i);
		}
		switch (val) {
		case CONTINUOUS:
			return cont_process_measurements;
		case SAMPLED_REACTOR_FEED_STREAM6:
			return reactor_feed_analysis_stream6;
		case SAMPLED_PURGE_GAS_ANALYSIS_STREAM9:
			return purge_gas_analysis_stream9;
		case SAMPLED_PRODUCT_ANALYSIS_STREAM11:
			return product_analysis_stream11;
		case CONTINUOUS_REACTOR_FEED_STREAM6:
			return cont_reactor_feed_analysis_stream6;
		case CONTINUOUS_PURGE_GAS_STREAM9:
			return cont_purge_gas_analysis_stream9;
		case CONTINUOUS_PRODUCT_ANALYSIS_STREAM11:
			return cont_product_analysis_stream11;
		default:
			break;
		}
		return null;
	}
	
	/*
	 * use `map`for 
	 * a) create an overall anomaly value using all double measurement values 
	 * b) to transform and again `map` the json to create single TimeStream Records
	 */
	@Override
	public List<TimestreamPoint> map(String jsonString) {
			HashMap<String, Object> map = new Gson().fromJson(jsonString,
					new TypeToken<HashMap<String, JsonElement>>() {
					}.getType());
			TimestreamPoint container = new TimestreamPoint();
			Map<String, String> analyzedMeasures1 = new HashMap<>(map.size());
			Map<String, String> analyzedMeasures2 = new HashMap<>(map.size());
			Map<String, String> analyzedMeasures3 = new HashMap<>(map.size());
			Map<String, String> timestreamMeasures = new HashMap<>(map.size());
			
			ArrayList<String> group1 = tepMeasureVariables(measureTypes.CONTINUOUS_REACTOR_FEED_STREAM6);
			ArrayList<String> group2 = tepMeasureVariables(measureTypes.CONTINUOUS_PURGE_GAS_STREAM9);
			ArrayList<String> group3 = tepMeasureVariables(measureTypes.CONTINUOUS_PRODUCT_ANALYSIS_STREAM11);

			
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				String key = entry.getKey();
				String value = entry.getValue().toString();
				if(group1.contains(key)) {
					analyzedMeasures1.put(key, value);
					}
				if(group2.contains(key)) {
					analyzedMeasures2.put(key, value);
					}
				if(group3.contains(key)) {
					analyzedMeasures3.put(key, value);
					}
				else {
					timestreamMeasures.put(key, value);
				}
				}
			
			container.setTime(Instant.now().toEpochMilli());
			container.setTimeUnit("MILLISECONDS");
			container.addDimension("factory", "test-factory-1");
			
			
			// #########
			// all analyzed measures to Double[]
			Double[] measureDoubleValues1 = new Double[analyzedMeasures1.size()];
			int i=0;
			for (Map.Entry<String, String> entry : analyzedMeasures1.entrySet()) {
				Double val = Double.parseDouble(entry.getValue());
				measureDoubleValues1[i]=val;
				i++;
			}
			List<String> anomalyScore1 = overallAnomalyRunner1.run(measureDoubleValues1);
			//logger.info("anomaly "+measureTypes.SAMPLED_REACTOR_FEED_STREAM6+ anomalyScore1.toString());
			timestreamMeasures.put("anomaly_score_stream6", anomalyScore1.get(0));
			
			// merge the two measurements hashmaps before creating TimestreamPoints to include all analyzed values
			timestreamMeasures.putAll(analyzedMeasures1);
			
			//#######
			
			// #########
			// all analyzed measures to Double[]
			Double[] measureDoubleValues2 = new Double[analyzedMeasures2.size()];
			i=0;
			for (Map.Entry<String, String> entry : analyzedMeasures2.entrySet()) {
				Double val = Double.parseDouble(entry.getValue());
				measureDoubleValues2[i]=val;
				i++;
			}
			List<String> anomalyScore2 = overallAnomalyRunner2.run(measureDoubleValues2);
			//logger.info("anomaly "+measureTypes.SAMPLED_PURGE_GAS_ANALYSIS_STREAM9+ anomalyScore2.toString());
			timestreamMeasures.put("anomaly_score_stream9", anomalyScore2.get(0));

			// merge the two measurements hashmaps before creating TimestreamPoints to include all analyzed values
			timestreamMeasures.putAll(analyzedMeasures2);

			//#######

			// #########
			// all analyzed measures to Double[]
			Double[] measureDoubleValues3 = new Double[analyzedMeasures3.size()];
			i=0;
			for (Map.Entry<String, String> entry : analyzedMeasures3.entrySet()) {
				Double val = Double.parseDouble(entry.getValue());
				measureDoubleValues3[i]=val;
				i++;
			}
			List<String> anomalyScore3 = overallAnomalyRunner3.run(measureDoubleValues3);
			//logger.info("anomaly "+measureTypes.SAMPLED_PRODUCT_ANALYSIS_STREAM11+ anomalyScore3.toString());
			timestreamMeasures.put("anomaly_score_stream_11", anomalyScore3.get(0));

			// merge the two measurements hashmaps before creating TimestreamPoints to include all analyzed values
			timestreamMeasures.putAll(analyzedMeasures3);

			//#######
			
			return timestreamMeasures.entrySet().stream().map(measure -> new TimestreamPoint(container, measure.getKey(),
					measure.getValue(), MeasureValueType.DOUBLE)).collect(Collectors.toList());
		
	}
}