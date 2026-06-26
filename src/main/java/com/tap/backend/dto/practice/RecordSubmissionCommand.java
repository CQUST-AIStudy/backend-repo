package com.tap.backend.dto.practice;

import com.tap.backend.domain.practice.WrongQuestionEntity;

public record RecordSubmissionCommand(
    String studentNo,
    Long problemId,
    String problemTitle,
    String problemSlug,
    String difficulty,
    String judgeStatus,
    String code,
    String errorMessage,
    Integer runtimeMs,
    Integer memoryKb,
    WrongQuestionEntity.SourceType sourceType
) {}
