package com.tap.backend.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.translation")
public record TranslationProperties(
    long cacheTtlSeconds,
    String provider,
    DeepLProperties deepl
) {}
