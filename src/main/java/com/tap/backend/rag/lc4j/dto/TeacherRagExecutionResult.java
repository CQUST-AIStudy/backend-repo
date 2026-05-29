package com.tap.backend.rag.lc4j.dto;

import java.util.List;

public record TeacherRagExecutionResult(
    String answerText,
    List<TeacherRagCitation> citations,
    List<Long> retrievedChunkIds,
    double top1Score,
    double coverageScore,
    String intentType,
    String effectiveMode,
    boolean usedWeb) {}
