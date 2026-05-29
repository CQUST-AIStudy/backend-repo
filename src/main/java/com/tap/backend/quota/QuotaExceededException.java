package com.tap.backend.quota;

public class QuotaExceededException extends RuntimeException {
  public QuotaExceededException(String message) {
    super(message);
  }
}
