package com.tap.backend.service.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.dto.grading.AgentConfigDto;
import com.tap.backend.dto.grading.BatchReviewDto;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.repo.ScoreItemRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GradingBatchReviewServiceTest {

    private GradingTaskRepository taskRepository;
    private GradingSubmissionRepository submissionRepository;
    private ScoreItemRepository scoreItemRepository;
    private GradingAgentConfigService configService;
    private AiProvider aiProvider;
    private GradingBatchReviewService service;
    private GradingTaskEntity task;

    @BeforeEach
    void setUp() {
        taskRepository = mock(GradingTaskRepository.class);
        submissionRepository = mock(GradingSubmissionRepository.class);
        scoreItemRepository = mock(ScoreItemRepository.class);
        configService = mock(GradingAgentConfigService.class);
        aiProvider = mock(AiProvider.class);
        service = new GradingBatchReviewService(
                taskRepository,
                submissionRepository,
                scoreItemRepository,
                configService,
                aiProvider,
                new ObjectMapper());

        task = new GradingTaskEntity();
        ReflectionTestUtils.setField(task, "id", 23L);
        task.setTotalCount(1);
        task.setCompletedCount(1);
        task.setScoreRangeMax(new BigDecimal("100"));
        when(taskRepository.findById(23L)).thenReturn(Optional.of(task));
    }

    @Test
    void failedReviewReturnsTheStoredErrorInsteadOfPending() {
        task.setBatchReviewStatus(GradingTaskEntity.BatchReviewStatus.FAILED);
        task.setBatchReviewJson("{\"errorMessage\":\"模型不支持 qwen-plus-latest\"}");

        BatchReviewDto result = service.getReview(23L);

        assertEquals("FAILED", result.status());
        assertEquals("模型不支持 qwen-plus-latest", result.errorMessage());
    }

    @Test
    void defaultBatchReviewUsesTheConfiguredProviderModel() {
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setStudentName("张三");
        submission.setTotalScore(new BigDecimal("86"));
        when(submissionRepository.findAllByTaskId(23L)).thenReturn(List.of(submission));
        when(scoreItemRepository.findAllBySubmissionId(any())).thenReturn(List.of());
        when(configService.findByCode("batch_review_default")).thenReturn(Optional.of(
                new AgentConfigDto(1L, "batch_review_default", "默认批次总评",
                        "{{submissionsSummary}}", "qwen-plus-latest",
                        new BigDecimal("0.30"), 1600, true)));
        when(aiProvider.model()).thenReturn("deepseek-v4-flash");
        when(aiProvider.chat(anyString(), eq("deepseek-v4-flash"))).thenReturn(
                "{\"summary\":\"整体完成情况良好\",\"commonIssues\":[],\"strengths\":[],\"teachingAdvice\":\"继续巩固\"}");

        service.internalGenerate(23L);

        verify(aiProvider).chat(anyString(), eq("deepseek-v4-flash"));
        assertEquals(GradingTaskEntity.BatchReviewStatus.COMPLETED, task.getBatchReviewStatus());
    }

    @Test
    void aiFailureStoresAndReturnsTheRealReason() {
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setStudentName("张三");
        submission.setTotalScore(new BigDecimal("86"));
        when(submissionRepository.findAllByTaskId(23L)).thenReturn(List.of(submission));
        when(scoreItemRepository.findAllBySubmissionId(any())).thenReturn(List.of());
        when(configService.findByCode("batch_review_default")).thenReturn(Optional.empty());
        when(aiProvider.model()).thenReturn("deepseek-v4-flash");
        when(aiProvider.chat(anyString(), eq("deepseek-v4-flash")))
                .thenThrow(new IllegalStateException("400 Bad Request: model unavailable"));

        service.generateAsync(23L);

        assertEquals(GradingTaskEntity.BatchReviewStatus.FAILED, task.getBatchReviewStatus());
        assertTrue(task.getBatchReviewJson().contains("400 Bad Request: model unavailable"));
        assertEquals("400 Bad Request: model unavailable", service.getReview(23L).errorMessage());
    }
}
