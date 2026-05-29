package com.tap.backend.service.ziporganize;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.zip-organize")
public record ZipOrganizeProperties(
    int jobMaxConcurrency,
    int itemMaxConcurrency,
    long pollIntervalMs,
    int maxFiles,
    long maxZipBytes,
    int extractTextMaxChars,
    int aiTextMaxChars,
    int retryLimit,
    double reviewThreshold
) {}
