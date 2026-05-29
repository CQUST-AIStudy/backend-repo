package com.tap.backend.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.agent")
public record AgentProperties(
    int jobMaxConcurrency,
    int docMaxConcurrency,
    long pollIntervalMs,
    int docTaskMaxRetries
) {}
