package com.tap.backend.dto.practice;

import java.time.Instant;
import java.util.List;

public record WrongQuestionDetailDto(
    Long id,
    Long problemId,
    String problemTitle,
    String problemSlug,
    String difficulty,
    String sourceType,
    String errorCategory,
    int totalWrongCount,
    int consecutiveAcCount,
    Instant firstWrongAt,
    Instant lastWrongAt,
    Instant lastAttemptAt,
    String lastJudgeStatus,
    String lastWrongCode,
    String lastErrorMessage,
    boolean resolved,
    Instant resolvedAt,
    String tagsCached,
    String notes,
    List<AttemptLogDto> recentAttempts
) {}
