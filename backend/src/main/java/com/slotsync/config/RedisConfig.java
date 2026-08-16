package com.slotsync.config;

import com.slotsync.stream.SseHub;
import com.slotsync.stream.StreamBroadcaster;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this replica to the live-event channel.
 *
 * <p>Together with {@link StreamBroadcaster} this gives the fan-out: any
 * replica can publish, every replica receives, and each one forwards to the
 * browsers it happens to be holding SSE connections for.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, SseHub sseHub) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(sseHub, new ChannelTopic(StreamBroadcaster.CHANNEL));
        return container;
    }
}
