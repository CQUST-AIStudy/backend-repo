package com.tap.backend.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.documents")
public record DocumentIngestProperties(
    int extractedTextMaxChars,
    boolean storeFullExtractedTextToMinio,
    boolean ocrEnabled,
    String ocrCommand,
    String ocrLanguage,
    int ocrMaxPages,
    boolean vlmFallbackEnabled,
    int vlmMaxPages
) {}
