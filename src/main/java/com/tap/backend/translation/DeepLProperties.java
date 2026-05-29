package com.tap.backend.translation;

public record DeepLProperties(
    String apiKey,
    String baseUrl,
    int maxBatchSize,
    long minIntervalMs
) {}
