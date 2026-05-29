package com.tap.backend.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.storage.s3")
public record S3Properties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket
) {}
