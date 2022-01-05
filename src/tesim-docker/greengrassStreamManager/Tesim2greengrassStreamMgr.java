/*Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: MIT-0*/

import com.amazonaws.greengrass.streammanager.client.StreamManagerClientFactory;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerClientConfig;
import com.amazonaws.greengrass.streammanager.client.config.StreamManagerServerInfo;
import com.amazonaws.greengrass.streammanager.client.StreamManagerClient;
import com.amazonaws.greengrass.streammanager.client.exception.StreamManagerException;
import com.amazonaws.greengrass.streammanager.model.MessageStreamDefinition;
import com.amazonaws.greengrass.streammanager.model.StrategyOnFull;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.logging.Logger;

public class Tesim2greengrassStreamMgr {
    private static final Logger logger = Logger.getLogger("Tesim2greengrassStreamMgr");

    private static final String STREAM_NAME = "TesimSourceStream";
    private static final  String GG_STREAM_HOST = "localhost";
    private static final  String GG_STREAM_PORT = "8088";
    static {
              System.setProperty("java.util.logging.SimpleFormatter.format",
              "[%1$tF %1$tT] [%4$-7s] %5$s %n");
            }

    public static void main(String[] args) throws Exception {
        logger.info("|--------------------------------------------------------|");
        logger.info("| TESIM realtime parameters to GGv2 StreamManager Stream |");
        logger.info("|   @------------------------------------------------@   |");
        logger.info("|            using `rtclient --stream-stdout`            |");
        logger.info("|--------------------------------------------------------|");
        final StreamManagerClientConfig config = StreamManagerClientConfig
                .builder()
                .serverInfo(StreamManagerServerInfo
                .builder().host(GG_STREAM_HOST).port(Integer.parseInt(GG_STREAM_PORT))
                .build())
                .build();
        try (final StreamManagerClient client = StreamManagerClientFactory.standard().withClientConfig(config).build()) {
            // Try deleting the stream (if it exists) so that we have a fresh start
            try {
                client.deleteMessageStream("GGTargetStream");
                client.deleteMessageStream("SomeStream");
                client.deleteMessageStream("TargetStream");
                client.deleteMessageStream("TesimSourceStream");
                client.deleteMessageStream("TesimTargetStream");
                
            } catch (StreamManagerException e) {
                logger.info(e.getMessage());
            }
            // now create StreamManager Stream
            try {
            client.createMessageStream(
                    new MessageStreamDefinition()
                            .withName(STREAM_NAME)
                            .withStrategyOnFull(StrategyOnFull.OverwriteOldestData));
            } catch (StreamManagerException e) {
                logger.warning(e.getMessage());
            }
            // execute `tesim && rtclient --stream-stdout`
            ProcessBuilder tesimProcess = new ProcessBuilder();
            tesimProcess.command("bash", "-c", "while true; do /home/dev1/tesim/c/tesim --ksave 2 --simtime 0.2 --real-time --shared-memory >> tesim.log; echo \"restarting tesim...\"; sleep 1; done");
            Process process1 = tesimProcess.start();
            Thread.sleep(2000);
            ProcessBuilder rtclientProcess = new ProcessBuilder();
            rtclientProcess.command("bash", "-c", "/home/dev1/tesim/c/rtclient/rtclient -s");
            Process process2 = rtclientProcess.start();

            BufferedReader reader2 = new BufferedReader(new InputStreamReader(process2.getInputStream()));
            String line2;
            
            int i = 0;
            while ((line2 = reader2.readLine()) != null) {
                // append to stream
                client.appendMessage(STREAM_NAME, line2.getBytes());
                i++;
                
                // log every 1000th msg
                if (i==1000){
                    logger.info(
                        String.format("appended %d messages: e.g. -> %s to %s",
                            i,
                            line2.substring(0,20)+"...",
                            STREAM_NAME));
                    i = 0;
                }
            }
        } catch (Exception e) {
            logger.warning(e.getMessage());
        }
    }
}
