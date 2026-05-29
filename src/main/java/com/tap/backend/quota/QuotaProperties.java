package com.tap.backend.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.quota")
public record QuotaProperties(
    long translationCharsPerDay,
    long aiRequestsPerDay,
    boolean adminUnlimited
) {}
