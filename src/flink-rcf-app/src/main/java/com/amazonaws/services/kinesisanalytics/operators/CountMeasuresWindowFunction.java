/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */
package com.amazonaws.services.kinesisanalytics.operators;

import java.util.List;

import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.timestream.TimestreamPoint;

@SuppressWarnings({ "serial" })
public class CountMeasuresWindowFunction
		extends ProcessAllWindowFunction<List<TimestreamPoint>, Integer, GlobalWindow> {

	private final Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public void process(Context context, Iterable<List<TimestreamPoint>> input, Collector<Integer> out) {
		

		int messages = 0;
		int measurements = 0;
		for (List<TimestreamPoint> in : input) {
			messages++;
			measurements = measurements+in.size();
		}
		logger.info("processed {} kinesis messages, containing {} measurements",messages, measurements);
		
		

		out.collect(messages);
	}

}

