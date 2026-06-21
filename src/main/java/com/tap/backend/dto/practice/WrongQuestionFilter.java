package com.tap.backend.dto.practice;

public record WrongQuestionFilter(
    Boolean resolved,
    String sourceType,
    String errorCategory,
    String difficulty,
    String tag,
    String q
) {}
