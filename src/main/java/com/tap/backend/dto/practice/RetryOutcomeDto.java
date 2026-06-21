package com.tap.backend.dto.practice;

public record RetryOutcomeDto(
    int newConsecutiveAcCount,
    boolean justResolved,
    boolean isResolved
) {}
