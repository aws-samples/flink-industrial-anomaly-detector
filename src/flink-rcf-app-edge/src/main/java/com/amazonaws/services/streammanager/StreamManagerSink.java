/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.streammanager;

import com.amazonaws.greengrass.streammanager.client.StreamManagerClient;
import com.amazonaws.greengrass.streammanager.client.StreamManagerClientFactory;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerClientConfig;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerServerInfo;
import com.amazonaws.greengrass.streammanager.client.exception.StreamManagerException;
import com.amazonaws.greengrass.streammanager.model.MessageStreamDefinition;
import com.amazonaws.greengrass.streammanager.model.Persistence;
import com.amazonaws.greengrass.streammanager.model.StrategyOnFull;
import com.amazonaws.greengrass.streammanager.model.export.ExportDefinition;
import com.amazonaws.greengrass.streammanager.model.export.KinesisConfig;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sink function for AWS IoT Greengrass v2 StreamManager
 */
public class StreamManagerSink extends RichSinkFunction<String> implements CheckpointedFunction {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2385268979569454729L;
	private static final long RECORDS_FLUSH_INTERVAL_MILLISECONDS = 60L * 1000L; // One minute
	private final Logger logger = LoggerFactory.getLogger(getClass());
	private final String region;
	private final String targetGGStream;
	private final String streamMgrHost;
	private final String streamMgrPort;
	private final String kinesisExportStreamName;
	private final Integer batchSize;
	private final BlockingQueue<String> bufferedRecords;
	private transient ListState<String> checkPointedState;
	private transient StreamManagerClient writeClient;
	private long emptyListTimestamp;

	public StreamManagerSink(String region, String targetGGStream, String streamMgrHost, String streamMgrPort, String kinesisExportStreamName, int batchSize) {
		this.region = region;
		this.targetGGStream = targetGGStream;
		this.streamMgrHost = streamMgrHost;
		this.streamMgrPort = streamMgrPort;
		this.kinesisExportStreamName = kinesisExportStreamName;
		this.batchSize = batchSize;
		this.bufferedRecords = new LinkedBlockingQueue<>();
		this.emptyListTimestamp = System.currentTimeMillis();
	}

	@SuppressWarnings("serial")
	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);

		final StreamManagerClientConfig clientConfig = StreamManagerClientConfig.builder()
				.logger(logger)
				.serverInfo(StreamManagerServerInfo.builder()
						.host(streamMgrHost)
						.port(Integer.parseInt(streamMgrPort)).build())
				.build();
		this.writeClient = StreamManagerClientFactory.standard().withClientConfig(clientConfig).build();
	
		// create target StreamManager stream
		try {
			this.writeClient.createMessageStream(new MessageStreamDefinition().withName(targetGGStream) // Required.
					.withMaxSize(268435456L) // Default is 256 MB.
					.withStreamSegmentSize(16777216L) // Default is 16 MB.
					.withTimeToLiveMillis(null) // By default, no TTL is enabled.
					.withStrategyOnFull(StrategyOnFull.OverwriteOldestData) // Required.
					.withPersistence(Persistence.File) // Default is File.
					.withFlushOnWrite(false) // Default is false.
					.withExportDefinition( // Optional. Choose where/how the stream is exported to the AWS Cloud.
							new ExportDefinition()
							.withKinesis(new ArrayList<KinesisConfig>() {
								{
									add(new KinesisConfig()
											.withIdentifier("KinesisExport" + kinesisExportStreamName)
											.withBatchSize(1L)
											.withKinesisStreamName(kinesisExportStreamName));
								}
							})
							.withIotAnalytics(null)
							.withIotSitewise(null)
							.withHttp(null)
							.withS3TaskExecutor(null)
					)

			);
		} catch (StreamManagerException e) {
			logger.warn(e.getMessage());
		}
	}

	@Override
	public void invoke(String points, @SuppressWarnings("rawtypes") Context context) {
		bufferedRecords.add(points);

		if (shouldPublish()) {
			while (!bufferedRecords.isEmpty()) {
				List<String> recordsToSend = new ArrayList<>(batchSize);
				bufferedRecords.drainTo(recordsToSend, batchSize);
				writeBatch(recordsToSend);
			}
		}
	}

	
	
	private void writeBatch(List<String> recordsToSend) {
		
			try {
				String stringRecs = String.join(", ", recordsToSend);
				//logger.info(stringRecs);
				this.writeClient.appendMessage(targetGGStream, stringRecs.getBytes());
				logger.info(String.format("appended 1 message having %s records to GG v2 stream %s (%s)", 
						recordsToSend.size(), 
						targetGGStream, 
						writeClient.toString()));
				emptyListTimestamp = System.currentTimeMillis();
			}  catch (StreamManagerException e) {
				// TODO Auto-generated catch block
				logger.error(e.getMessage());
			}	
	}

	

	// Method to validate if record batch should be published.
	// This method would return true if the accumulated records has reached the
	// batch size.
	// Or if records have been accumulated for last
	// RECORDS_FLUSH_INTERVAL_MILLISECONDS time interval.
	private boolean shouldPublish() {
		if (bufferedRecords.size() >= batchSize) {
			logger.debug("Batch of size " + bufferedRecords.size() + " should get published");
			return true;
		} else if (System.currentTimeMillis() - emptyListTimestamp >= RECORDS_FLUSH_INTERVAL_MILLISECONDS) {
			logger.debug("Records after flush interval should get published");
			return true;
		}
		return false;
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
}