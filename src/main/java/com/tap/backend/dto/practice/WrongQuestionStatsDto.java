package com.tap.backend.dto.practice;

import java.util.Map;

public record WrongQuestionStatsDto(
    long total,
    long unresolved,
    long resolved,
    Map<String, Long> byDifficulty,
    Map<String, Long> byErrorCategory
) {}
