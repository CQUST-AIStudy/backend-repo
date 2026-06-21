package com.tap.backend.dto.practice;

import com.tap.backend.domain.practice.WrongQuestionEntity;

public record RecordSubmissionDto(
    Long problemId,
    String problemTitle,
    String problemSlug,
    String difficulty,
    String judgeStatus,
    String code,
    String errorMessage,
    Integer runtimeMs,
    Integer memoryKb,
    String sourceType
) {
  public RecordSubmissionCommand toCommand(String studentNo) {
    WrongQuestionEntity.SourceType parsed;
    if (sourceType == null || sourceType.isBlank()) {
      parsed = WrongQuestionEntity.SourceType.LEETCODE_PRACTICE;
    } else {
      try {
        parsed = WrongQuestionEntity.SourceType.valueOf(sourceType.toUpperCase());
      } catch (IllegalArgumentException ex) {
        parsed = WrongQuestionEntity.SourceType.LEETCODE_PRACTICE;
      }
    }
    return new RecordSubmissionCommand(
        studentNo,
        problemId,
        problemTitle,
        problemSlug,
        difficulty,
        judgeStatus,
        code,
        errorMessage,
        runtimeMs,
        memoryKb,
        parsed
    );
  }
}
