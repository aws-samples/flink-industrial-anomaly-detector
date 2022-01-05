/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.streammanager;


import com.amazonaws.greengrass.streammanager.client.StreamManagerClient;
import com.amazonaws.greengrass.streammanager.client.StreamManagerClientFactory;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerClientConfig;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerServerInfo;
import com.amazonaws.greengrass.streammanager.client.exception.NotEnoughMessagesException;
import com.amazonaws.greengrass.streammanager.model.Message;
import com.amazonaws.greengrass.streammanager.model.MessageStreamInfo;
import com.amazonaws.greengrass.streammanager.model.ReadMessagesOptions;


import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Source function for AWS IoT Greengrass v2 StreamManager
 */
public class StreamManagerSource extends RichSourceFunction<String> implements CheckpointedFunction {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2385268979569454729L;
	private final Logger logger = LoggerFactory.getLogger(getClass());
	private final String ggSourceStreamName;
	private final String streamMgrHost;
	private final String streamMgrPort;
	private final BlockingQueue<String> bufferedRecords;
	private transient ListState<String> checkPointedState;
	private transient StreamManagerClient readClient;
	private volatile boolean isRunning = true;

	public StreamManagerSource(String ggSourceStreamName, String streamMgrHost, String streamMgrPort) {
		this.ggSourceStreamName = ggSourceStreamName;
		this.streamMgrHost = streamMgrHost;
		this.streamMgrPort = streamMgrPort;
		this.bufferedRecords = new LinkedBlockingQueue<>();
	}
	
	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);

		final StreamManagerClientConfig clientConfig = StreamManagerClientConfig.builder()
				.logger(logger)
				.serverInfo(StreamManagerServerInfo.builder()
						.host(streamMgrHost)
						.port(Integer.parseInt(streamMgrPort)).build())
				.build();
		this.readClient = StreamManagerClientFactory.standard().withClientConfig(clientConfig).build();
	}

	@Override
	public void run(SourceContext<String> ctx) throws Exception {
		 MessageStreamInfo description = readClient.describeMessageStream(ggSourceStreamName);
    	 logger.info(description.toString());
    	 Long startSequence = description.getStorageStatus().getNewestSequenceNumber();
    	 while (isRunning) {
                // get latest messages from GG v2 StreamManager stream
            	try {
					List<Message> ggStreamMsgs = readClient.readMessages(ggSourceStreamName, 
							new ReadMessagesOptions()
							// Try to read from sequence number 100 or greater. By default this is 0.
					        .withDesiredStartSequenceNumber(startSequence)
					        // Try to read 10 messages. If 10 messages are not available, then NotEnoughMessagesException is raised. By default, this is 1.
					        .withMinMessageCount(15L)
					        // Accept up to 100 messages. By default this is 1.
					        .withMaxMessageCount(100L)
					        // Try to wait at most 5 seconds for the min_messsage_count to be fulfilled. By default, this is 0, which immediately returns the messages or an exception.
					        .withReadTimeoutMillis(Duration.ofSeconds(1L).toMillis()));
					//System.out.println(String.format("Successfully read 4 messages: %s", ggStreamMsgs));
					for (Message msg : ggStreamMsgs) {
						String s = new String(msg.getPayload());
						//Long count = msg.getSequenceNumber();
						//logger.info(count+":->"+s);
						ctx.collect(s);
					}
					startSequence = startSequence + ggStreamMsgs.size();
				} catch (NotEnoughMessagesException e) {
					// TODO Auto-generated catch block
					logger.warn(e.getMessage());
					description = readClient.describeMessageStream(ggSourceStreamName);
					//logger.info(description.toString());
			    	startSequence = description.getStorageStatus().getNewestSequenceNumber();
					continue;
				}
         }
		
	}

	@Override
	public void close() throws Exception {
		super.close();
	}

	@Override
	public void snapshotState(FunctionSnapshotContext functionSnapshotContext) throws Exception {
		checkPointedState.clear();
		for (String bufferedRecord : bufferedRecords) {
			checkPointedState.add(bufferedRecord);
		}
	}

	@Override
	public void initializeState(FunctionInitializationContext functionInitializationContext) throws Exception {
		ListStateDescriptor<String> descriptor = new ListStateDescriptor<>("recordList", String.class);

		checkPointedState = functionInitializationContext.getOperatorStateStore().getListState(descriptor);

		if (functionInitializationContext.isRestored()) {
			for (String element : checkPointedState.get()) {
				bufferedRecords.add(element);
			}
		}
	}

	@Override
	public void cancel() {
		  isRunning = false;
	}

	

	
}