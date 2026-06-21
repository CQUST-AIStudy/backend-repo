package com.tap.backend.dto.practice;

public record WrongQuestionRetryRequest(
    String judgeStatus,
    String code,
    Integer runtimeMs,
    Integer memoryKb
) {}
