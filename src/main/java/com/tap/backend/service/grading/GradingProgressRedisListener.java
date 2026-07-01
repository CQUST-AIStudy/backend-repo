package com.tap.backend.service.grading;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 订阅 worker 推送的细粒度阶段进度频道 {@code grading:progress}，
 * 交由 {@link GradingProgressService} 按任务广播到对应的 SSE 订阅者。
 */
@Configuration
public class GradingProgressRedisListener {

    private static final String PROGRESS_CHANNEL = "grading:progress";

    @Bean
    @ConditionalOnProperty(prefix = "tap.grading.redis-listener", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer gradingProgressListenerContainer(
            RedisConnectionFactory connectionFactory,
            GradingProgressService progressService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        MessageListener listener = (message, pattern) ->
                progressService.onProgressMessage(new String(message.getBody()));

        container.addMessageListener(listener, new ChannelTopic(PROGRESS_CHANNEL));
        return container;
    }
}
