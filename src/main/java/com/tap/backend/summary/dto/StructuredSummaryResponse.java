package com.tap.backend.summary.dto;

public record StructuredSummaryResponse(
    String scopeType,
    String scopeKey,
    String provider,
    String model,
    int charCountZh,
    StructuredSummaryDto structured,
    String markdown
) {}
