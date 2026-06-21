package com.tap.backend.dto.practice;

import java.time.Instant;

public record AttemptLogDto(
    Instant attemptAt,
    String judgeStatus,
    boolean wasAc,
    Integer runtimeMs,
    Integer memoryKb,
    String source
) {}
