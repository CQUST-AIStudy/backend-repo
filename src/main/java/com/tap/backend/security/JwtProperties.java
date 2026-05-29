package com.tap.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.security.jwt")
public record JwtProperties(
    String issuer,
    String secret,
    long accessTokenTtlSeconds
) {}
