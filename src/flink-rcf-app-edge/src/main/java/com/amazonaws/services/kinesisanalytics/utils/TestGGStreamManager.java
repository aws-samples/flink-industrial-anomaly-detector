/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0 */

package com.amazonaws.services.kinesisanalytics.utils;

import com.amazonaws.greengrass.streammanager.client.StreamManagerClientFactory;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerClientConfig;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerServerInfo;
import com.amazonaws.greengrass.streammanager.client.StreamManagerClient;
import com.amazonaws.greengrass.streammanager.client.exception.StreamManagerException;
import com.amazonaws.greengrass.streammanager.model.MessageStreamDefinition;
import com.amazonaws.greengrass.streammanager.model.StrategyOnFull;
import java.io.BufferedReader;
import java.io.InputStreamReader;


public class TestGGStreamManager {
	private static final String STREAM_NAME = "TesimSourceStream";
    private static final  String GG_STREAM_HOST = "localhost";
    private static final  String GG_STREAM_PORT = "8088";

    public static void main(String[] args) throws Exception {
        final StreamManagerClientConfig config = StreamManagerClientConfig
                .builder()
                .serverInfo(StreamManagerServerInfo
                .builder().host(GG_STREAM_HOST).port(Integer.parseInt(GG_STREAM_PORT))
                .build())
                .build();
        try (final StreamManagerClient client = StreamManagerClientFactory.standard().withClientConfig(config).build()) {
            // Try deleting the stream (if it exists) so that we have a fresh start
            try {
                client.deleteMessageStream(STREAM_NAME);
            } catch (StreamManagerException e) {
                System.out.println(e.getMessage());
                for(StackTraceElement element: e.getStackTrace()) {
                    System.out.println(element);
                }
            }
            // now create StreamManager Stream
            client.createMessageStream(
                    new MessageStreamDefinition()
                            .withName(STREAM_NAME)
                            .withStrategyOnFull(StrategyOnFull.OverwriteOldestData));
            // execute `rtclient --stream-stdout`
            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command("bash", "-c", "/home/dev1/tesim/c/rtclient/rtclient -s");
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String line;

            // Now start putting in random data between 0 and 255 to emulate device sensor input
            
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                client.appendMessage(STREAM_NAME, line.getBytes());
                //Thread.sleep(1000);
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
            for(StackTraceElement element: e.getStackTrace()) {
                System.out.println(element);
            }
            // Properly handle exception
        }
    }
}


