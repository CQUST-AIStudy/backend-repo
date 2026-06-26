package com.tap.backend.dto.practice;

import java.time.Instant;

public record WrongQuestionListItemDto(
    Long id,
    Long problemId,
    String problemTitle,
    String problemSlug,
    String difficulty,
    String sourceType,
    String errorCategory,
    int totalWrongCount,
    int consecutiveAcCount,
    Instant lastWrongAt,
    Instant lastAttemptAt,
    boolean resolved,
    String tagsCached,
    String notes
) {}
