/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.camel.kafkaconnector.source.aws.kinesis;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.CreateStreamResult;
import com.amazonaws.services.kinesis.model.PutRecordsRequest;
import com.amazonaws.services.kinesis.model.PutRecordsRequestEntry;
import com.amazonaws.services.kinesis.model.PutRecordsResult;
import org.apache.camel.kafkaconnector.AbstractKafkaTest;
import org.apache.camel.kafkaconnector.ConnectorPropertyFactory;
import org.apache.camel.kafkaconnector.ContainerUtil;
import org.apache.camel.kafkaconnector.TestCommon;
import org.apache.camel.kafkaconnector.clients.kafka.KafkaClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
public class CamelSourceAWSKinesisITCase extends AbstractKafkaTest {
    private static final Logger LOG = LoggerFactory.getLogger(CamelSourceAWSKinesisITCase.class);
    private static final int KINESIS_PORT = 4568;

    @Container
    public LocalStackContainer localStackContainer = new LocalStackContainer()
            .withServices(LocalStackContainer.Service.KINESIS);

    private AmazonKinesis awsKinesisClient;

    private volatile int received;
    private final int expect = 10;


    @BeforeEach
    public void setUp() {
        if (!localStackContainer.isRunning()) {
            LOG.info("Kinesis is not running");
        }
        final String kinesisInstance = localStackContainer
                .getEndpointConfiguration(LocalStackContainer.Service.KINESIS)
                .getServiceEndpoint();

        LOG.info("Kinesis instance running at {}", kinesisInstance);

        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setProtocol(Protocol.HTTP);

        awsKinesisClient = AmazonKinesisClientBuilder
                .standard()
                .withEndpointConfiguration(localStackContainer.getEndpointConfiguration(LocalStackContainer.Service.KINESIS))
                .withCredentials(localStackContainer.getDefaultCredentialsProvider())
                .withClientConfiguration(clientConfiguration)
                .build();
    }

    private boolean checkRecord(ConsumerRecord<String, String> record) {
        LOG.debug("Received: {}", record.value());
        received++;

        if (received == expect) {
            return false;
        }

        return true;
    }

    @Test
    @Timeout(120)
    public void testBasicSendReceive() throws ExecutionException, InterruptedException {
        Properties properties = ContainerUtil.setupAWSConfigs(localStackContainer, KINESIS_PORT);

        ConnectorPropertyFactory testProperties = new CamelAWSKinesisPropertyFactory(1,
                TestCommon.getDefaultTestTopic(this.getClass()), TestCommon.DEFAULT_KINESIS_STREAM, properties);

        getKafkaConnectService().initializeConnectorBlocking(testProperties);

        putRecords();
        LOG.debug("Initialized the connector and put the data for the test execution");

        LOG.debug("Creating the consumer ...");
        KafkaClient<String, String> kafkaClient = new KafkaClient<>(getKafkaService().getBootstrapServers());
        kafkaClient.consume(TestCommon.getDefaultTestTopic(this.getClass()), this::checkRecord);
        LOG.debug("Created the consumer ...");

        assertEquals(received, expect, "Didn't process the expected amount of messages");
    }

    private void putRecords() {
        CreateStreamResult result = awsKinesisClient.createStream(TestCommon.DEFAULT_KINESIS_STREAM, 1);
        if (result.getSdkHttpMetadata().getHttpStatusCode() != 200) {
            fail("Failed to create the stream");
        } else {
            LOG.info("Stream created successfully");
        }

        PutRecordsRequest putRecordsRequest = new PutRecordsRequest();
        putRecordsRequest.setStreamName(TestCommon.DEFAULT_KINESIS_STREAM);

        List<PutRecordsRequestEntry> putRecordsRequestEntryList = new ArrayList<>();

        LOG.debug("Adding data to the Kinesis stream");
        for (int i = 0; i < expect; i++) {
            PutRecordsRequestEntry putRecordsRequestEntry = new PutRecordsRequestEntry();
            putRecordsRequestEntry.setData(ByteBuffer.wrap(String.valueOf(i).getBytes()));

            String partition = String.format("partitionKey-%d", i);
            putRecordsRequestEntry.setPartitionKey(partition);

            LOG.debug("Added data {} (as bytes) to partition {}", i, partition);

            putRecordsRequestEntryList.add(putRecordsRequestEntry);
        }

        LOG.debug("Done creating the data records");

        int retries = 5;
        do {
            try {
                putRecordsRequest.setRecords(putRecordsRequestEntryList);
                PutRecordsResult putRecordsResult = awsKinesisClient.putRecords(putRecordsRequest);

                if (putRecordsResult.getFailedRecordCount() == 0) {
                    LOG.debug("Done putting the data records into the stream");
                } else {
                    fail("Unable to put all the records into the stream");
                }

                break;
            } catch (AmazonServiceException e) {
                retries--;

                /*
                 This works around the "... Cannot deserialize instance of `...AmazonKinesisException` out of NOT_AVAILABLE token

                 It may take some time for the local Kinesis backend to be fully up - even though the container is
                 reportedly up and running. Therefore, it tries a few more times
                 */

                LOG.error("Failed to put the records: {}", e.getMessage(), e);
                if (retries == 0) {
                    throw e;
                }


                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException ex) {
                    break;
                }
            }
        } while (retries > 0);


    }
}
