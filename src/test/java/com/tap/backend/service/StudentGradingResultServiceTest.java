package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.repo.EvidenceBlockRepository;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.ReportFileRepository;
import com.tap.backend.repo.ScoreItemRepository;
import com.tap.backend.infra.storage.ObjectStorageService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StudentGradingResultServiceTest {

    @Test
    void findsLatestPublishedResultWhenTaskIsNotLinkedToExperiment() {
        GradingSubmissionRepository submissionRepo = mock(GradingSubmissionRepository.class);
        ReportFileRepository reportFileRepo = mock(ReportFileRepository.class);
        ScoreItemRepository scoreItemRepo = mock(ScoreItemRepository.class);
        EvidenceBlockRepository evidenceBlockRepo = mock(EvidenceBlockRepository.class);
        GradingErrorDemonstrationService errorDemonstrationService = mock(GradingErrorDemonstrationService.class);

        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        ReflectionTestUtils.setField(submission, "id", 63L);
        submission.setStudentNo("20230001");
        submission.setStudentName("陈一鸣");
        submission.setTotalScore(new BigDecimal("72"));
        submission.setFinalReviewComment("已发布的教师总评");
        submission.setPublishedAt(Instant.parse("2026-06-22T10:00:00Z"));

        when(submissionRepo.findPublishedForStudentExperiment("20230001", 1L)).thenReturn(List.of());
        when(submissionRepo.findLatestPublishedForStudent("20230001")).thenReturn(List.of(submission));
        when(reportFileRepo.findAllBySubmissionIdOrderByCreatedAtDesc(63L)).thenReturn(List.of());
        when(scoreItemRepo.findAllBySubmissionId(63L)).thenReturn(List.of());
        when(evidenceBlockRepo.findAllBySubmissionId(63L)).thenReturn(List.of());
        when(errorDemonstrationService.buildDemonstrations(submission, List.of(), List.of())).thenReturn(List.of());

        StudentGradingResultService service = new StudentGradingResultService(
                submissionRepo,
                reportFileRepo,
                mock(ObjectStorageService.class),
                scoreItemRepo,
                evidenceBlockRepo,
                errorDemonstrationService
        );

        Map<String, Object> result = service.findPublishedResult("20230001", 1L);

        assertEquals(true, result.get("published"));
        assertEquals(63L, result.get("submissionId"));
        assertEquals(new BigDecimal("72"), result.get("score"));
        assertTrue(String.valueOf(result.get("finalReviewComment")).contains("已发布"));
    }
}
