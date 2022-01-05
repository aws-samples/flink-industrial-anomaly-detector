/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.kinesisanalytics.operators;

import java.io.PrintWriter;
import java.util.List;
import java.util.function.Function;

import org.apache.flink.api.java.utils.ParameterTool;
import com.amazon.randomcutforest.RandomCutForest;
import com.amazon.randomcutforest.runner.LineTransformer;
import com.amazon.randomcutforest.util.ShingleBuilder;

public class AnomalyDetector {
    protected final ParameterTool parameter;
    protected final Function<RandomCutForest, LineTransformer> algorithmInitializer;
    protected LineTransformer algorithm;
    protected ShingleBuilder shingleBuilder;
    protected double[] pointBuffer;
    protected double[] shingleBuffer;

    public AnomalyDetector(ParameterTool parameter, Function<RandomCutForest, LineTransformer> algorithmInitializer) {
    	this.parameter = parameter;
        this.algorithmInitializer = algorithmInitializer;
    }
  
    public List<String> run(Double[] values) {
            if (pointBuffer == null) {
                prepareAlgorithm(values.length);
            }
            
			return processLine(values);
    }

    protected void prepareAlgorithm(int dimensions) {
        pointBuffer = new double[dimensions];
        shingleBuilder = new ShingleBuilder(dimensions, Integer.parseInt(parameter.get("RcfShingleSize", "1")),
        		Boolean.parseBoolean(parameter.get("RcfShingleCyclic", "false")));
        shingleBuffer = new double[shingleBuilder.getShingledPointSize()];

        RandomCutForest forest = RandomCutForest.builder()
        		.numberOfTrees(Integer.parseInt(parameter.get("RcfNumberOfTrees", "50")))
                .sampleSize(Integer.parseInt(parameter.get("RcfSampleSize", "128")))
                .dimensions(shingleBuilder.getShingledPointSize())
                .lambda(Double.parseDouble(parameter.get("RcfLambda", "0.00078125")))
                .randomSeed(Integer.parseInt(parameter.get("RcfRandomSeed", "42")))
                .build();

        algorithm = algorithmInitializer.apply(forest);
    }
   
    protected List<String> processLine(Double[] values) {
        if (values.length != pointBuffer.length) {
            throw new IllegalArgumentException(
                    String.format("Wrong number of values. Exected %d but found %d.",
                            pointBuffer.length, values.length));
        }

        addPoint(values);
        shingleBuilder.addPoint(pointBuffer);

        List<String> result;
        if (shingleBuilder.isFull()) {
            shingleBuilder.getShingle(shingleBuffer);
            result = algorithm.getResultValues(shingleBuffer);
        } else {
            result = algorithm.getEmptyResultValue();
        }

		return result;
    }

   
    protected void addPoint(Double[] val) {
        for (int i = 0; i < pointBuffer.length; i++) {
            pointBuffer[i] = val[i];
        }
    }

    protected void finish(PrintWriter out) {

    }

 
    protected int getPointSize() {
        return pointBuffer != null ? pointBuffer.length : 0;
    }

    protected int getShingleSize() {
        return shingleBuffer != null ? shingleBuffer.length : 0;
    }
}
