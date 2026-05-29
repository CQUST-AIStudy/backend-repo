package com.tap.common.api;

import java.time.Instant;

public record ProblemResponse(
    String code,
    String message,
    Instant timestamp
) {
  public static ProblemResponse of(String code, String message) {
    return new ProblemResponse(code, message, Instant.now());
  }
}
