package com.tap.backend.rag.lc4j.dto;

import com.tap.backend.domain.rag.CourseSpaceEntity;
import com.tap.backend.rag.IntentClassifyService;
import com.tap.backend.rag.ModeDecisionService;
import com.tap.backend.rag.lc4j.retriever.TeacherHybridRetriever;
import com.tap.backend.service.CourseSpaceService;

public record TeacherRagExecutionContext(
    long courseSpaceId,
    String query,
    String requestedMode,
    CourseSpaceEntity courseSpace,
    CourseSpaceService.RagChatScope chatScope,
    IntentClassifyService.IntentResult intent,
    TeacherHybridRetriever.RetrievalResult retrieval,
    double coverageScore,
    ModeDecisionService.ModeDecision modeDecision,
    boolean usedWeb,
    String contextBlock,
    String systemPrompt) {}
