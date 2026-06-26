package com.tap.backend.service.practice.impl;

/**
 * Classifies judge status strings into a small set of error categories used by the
 * wrong-question notebook for filtering and analytics.
 */
public final class ErrorCategoryUtil {

  private ErrorCategoryUtil() {}

  public static final String WRONG_ANSWER = "WRONG_ANSWER";
  public static final String COMPILE_ERROR = "COMPILE_ERROR";
  public static final String RUNTIME_ERROR = "RUNTIME_ERROR";
  public static final String TIME_LIMIT_EXCEEDED = "TIME_LIMIT_EXCEEDED";
  public static final String MEMORY_LIMIT_EXCEEDED = "MEMORY_LIMIT_EXCEEDED";
  public static final String UNKNOWN = "UNKNOWN";

  public static String classify(String judgeStatus, String errorMessage) {
    String status = judgeStatus == null ? "" : judgeStatus.trim().toUpperCase();
    String msg = errorMessage == null ? "" : errorMessage.toLowerCase();

    if (status.isEmpty() && msg.isEmpty()) return UNKNOWN;

    if (containsAny(status, "WRONG_ANSWER", "WRONG ANSWER", "WA")
        || msg.contains("wrong answer") || msg.contains("incorrect output")) {
      return WRONG_ANSWER;
    }
    if (containsAny(status, "COMPILE_ERROR", "COMPILE ERROR", "CE", "COMPILE")
        || msg.contains("compile error") || msg.contains("syntax error")
        || msg.contains("compilation failed")) {
      return COMPILE_ERROR;
    }
    if (containsAny(status, "RUNTIME_ERROR", "RUNTIME", "RE")
        || msg.contains("runtime error") || msg.contains("exception")
        || msg.contains("null pointer") || msg.contains("segmentation fault")
        || msg.contains("index out of bounds")) {
      return RUNTIME_ERROR;
    }
    if (containsAny(status, "TIME_LIMIT_EXCEEDED", "TIME LIMIT", "TLE", "TIMEOUT")
        || msg.contains("time limit") || msg.contains("timed out")) {
      return TIME_LIMIT_EXCEEDED;
    }
    if (containsAny(status, "MEMORY_LIMIT_EXCEEDED", "MEMORY LIMIT", "MLE", "OUT_OF_MEMORY")
        || msg.contains("memory limit") || msg.contains("out of memory")) {
      return MEMORY_LIMIT_EXCEEDED;
    }
    if (containsAny(status, "FAILED", "FAIL", "ERROR")) {
      return UNKNOWN;
    }
    return UNKNOWN;
  }

  private static boolean containsAny(String haystack, String... needles) {
    if (haystack.isEmpty()) return false;
    for (String n : needles) {
      if (haystack.contains(n)) return true;
    }
    return false;
  }
}
