package com.slotsync.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring: topics, retry policy, dead-letter routing.
 *
 * <p>Only active when the Kafka transport is selected, so tests running with
 * the in-memory transport do not need a broker.
 */
@Configuration
@ConditionalOnProperty(name = "slotsync.events.transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaConfig {

    /**
     * Three partitions so three consumer instances can work in parallel.
     * Because every event is keyed by resource id, all events for one resource
     * still land on the same partition and stay in order - parallel across
     * resources, sequential within one.
     */
    @Bean
    public NewTopic eventsTopic(SlotSyncProperties properties) {
        return TopicBuilder.name(properties.events().topic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic deadLetterTopic(SlotSyncProperties properties) {
        return TopicBuilder.name(properties.events().dltTopic())
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Retry a failing record with exponential backoff, then give up and send it
     * to the dead-letter topic instead of blocking the partition forever.
     *
     * <p>Without this, one poison message stops every later message on that
     * partition - a classic production outage.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                                 SlotSyncProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        properties.events().dltTopic(), 0));

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(30_000L);   // ~1s, 2s, 4s, 8s, 16s then DLT

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
