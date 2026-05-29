package com.tap.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.math.BigDecimal;

@Configuration
public class RedisGradingListener {
    private static final Logger log = LoggerFactory.getLogger(RedisGradingListener.class);
    private static final String RESULTS_CHANNEL = "grading:results";

    @Bean
    @ConditionalOnProperty(prefix = "tap.grading.redis-listener", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer gradingListenerContainer(
            RedisConnectionFactory connectionFactory,
            GradingTaskService gradingTaskService,
            ObjectMapper objectMapper) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody());
                JsonNode node = objectMapper.readTree(body);
                Long submissionId = node.get("submissionId").asLong();
                String status = node.get("status").asText();
                BigDecimal totalScore = node.has("totalScore") && !node.get("totalScore").isNull()
                        ? new BigDecimal(node.get("totalScore").asText()) : null;
                gradingTaskService.onSubmissionComplete(submissionId, status, totalScore);
                log.info("Grading result received: submission={}, status={}", submissionId, status);
            } catch (Exception e) {
                log.error("Failed to process grading result notification", e);
            }
        };

        container.addMessageListener(listener, new ChannelTopic(RESULTS_CHANNEL));
        return container;
    }
}
