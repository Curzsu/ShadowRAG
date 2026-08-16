package com.yizhaoqi.smartpai.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KafkaConfigReliabilityTest {

    @Test
    void consumerUsesSingleRecordPollAndTenMinutePollInterval() {
        KafkaConfig config = configuredKafkaConfig();

        DefaultKafkaConsumerFactory<String, Object> consumerFactory =
                (DefaultKafkaConsumerFactory<String, Object>) config.consumerFactory();

        assertEquals(1, consumerFactory.getConfigurationProperties().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        assertEquals(600000,
                consumerFactory.getConfigurationProperties().get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
    }

    @Test
    void producerFactoryDoesNotRequireTransactionalSend() {
        KafkaConfig config = configuredKafkaConfig();

        DefaultKafkaProducerFactory<String, Object> producerFactory =
                (DefaultKafkaProducerFactory<String, Object>) config.producerFactory();

        assertFalse(producerFactory.transactionCapable());
    }

    private KafkaConfig configuredKafkaConfig() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "fileProcessingTopic", "file-processing-topic1");
        ReflectionTestUtils.setField(config, "fileProcessingDltTopic", "file-processing-dlt");
        ReflectionTestUtils.setField(config, "fileProcessingGroupId", "file-processing-group");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");
        ReflectionTestUtils.setField(config, "trustedPackages", "*");
        return config;
    }
}
