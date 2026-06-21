package com.tap.backend.service.practice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.practice.WrongQuestionAttemptLogEntity;
import com.tap.backend.domain.practice.WrongQuestionEntity;
import com.tap.backend.dto.practice.RecordSubmissionCommand;
import com.tap.backend.dto.practice.RetryOutcomeDto;
import com.tap.backend.dto.practice.WrongQuestionRetryRequest;
import com.tap.backend.repository.practice.WrongQuestionAttemptLogRepository;
import com.tap.backend.repository.practice.WrongQuestionRepository;
import com.tap.backend.service.practice.impl.WrongQuestionServiceImpl;
import com.tap.backend.service.practice.impl.WrongQuestionTagLookupService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WrongQuestionServiceTest {

  private static final String STUDENT_NO = "20210001";
  private static final Long PROBLEM_ID = 100L;

  @Mock private WrongQuestionRepository repo;
  @Mock private WrongQuestionAttemptLogRepository logRepo;
  @Mock private WrongQuestionTagLookupService tagLookupService;

  private WrongQuestionServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new WrongQuestionServiceImpl(repo, logRepo, tagLookupService);
  }

  @Test
  void recordSubmission_firstWrongInsertsRow() {
    when(repo.findByStudentNoAndProblemIdAndSourceType(
        eq(STUDENT_NO), eq(PROBLEM_ID), eq(WrongQuestionEntity.SourceType.LEETCODE_PRACTICE)))
        .thenReturn(Optional.empty());
    when(tagLookupService.lookupTagsCsv(PROBLEM_ID)).thenReturn("Array,DP");

    service.recordSubmission(new RecordSubmissionCommand(
        STUDENT_NO, PROBLEM_ID, "Two Sum", "id:100", "Easy",
        "WRONG_ANSWER", "int x =", "expected 0 but got 1",
        null, null, WrongQuestionEntity.SourceType.LEETCODE_PRACTICE));

    ArgumentCaptor<WrongQuestionEntity> captor = ArgumentCaptor.forClass(WrongQuestionEntity.class);
    verify(repo).save(captor.capture());
    WrongQuestionEntity saved = captor.getValue();
    assertEquals(STUDENT_NO, saved.getStudentNo());
    assertEquals(PROBLEM_ID, saved.getProblemId());
    assertEquals(1, saved.getTotalWrongCount());
    assertEquals(0, saved.getConsecutiveAcCount());
    assertFalse(saved.isResolved());
    assertEquals("WRONG_ANSWER", saved.getErrorCategory());
    assertEquals("Array,DP", saved.getTagsCached());
    verify(logRepo).save(any(WrongQuestionAttemptLogEntity.class));
  }

  @Test
  void recordSubmission_secondWrongIncrementsCount() {
    WrongQuestionEntity existing = new WrongQuestionEntity();
    existing.setId(7L);
    existing.setStudentNo(STUDENT_NO);
    existing.setProblemId(PROBLEM_ID);
    existing.setSourceType(WrongQuestionEntity.SourceType.LEETCODE_PRACTICE);
    existing.setTotalWrongCount(1);
    existing.setConsecutiveAcCount(0);
    when(repo.findByStudentNoAndProblemIdAndSourceType(
        eq(STUDENT_NO), eq(PROBLEM_ID), eq(WrongQuestionEntity.SourceType.LEETCODE_PRACTICE)))
        .thenReturn(Optional.of(existing));

    service.recordSubmission(new RecordSubmissionCommand(
        STUDENT_NO, PROBLEM_ID, "Two Sum", "id:100", "Easy",
        "WRONG_ANSWER", "code", "wrong",
        null, null, WrongQuestionEntity.SourceType.LEETCODE_PRACTICE));

    ArgumentCaptor<WrongQuestionEntity> captor = ArgumentCaptor.forClass(WrongQuestionEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(2, captor.getValue().getTotalWrongCount());
    assertEquals(0, captor.getValue().getConsecutiveAcCount());
    assertFalse(captor.getValue().isResolved());
    verify(tagLookupService, never()).lookupTagsCsv(any());
  }

  @Test
  void recordSubmission_acceptedIsNoOp() {
    service.recordSubmission(new RecordSubmissionCommand(
        STUDENT_NO, PROBLEM_ID, "Two Sum", "id:100", "Easy",
        "ACCEPTED", "code", null,
        null, null, WrongQuestionEntity.SourceType.LEETCODE_PRACTICE));

    verify(repo, never()).save(any());
    verify(logRepo, never()).save(any());
    verify(repo, never()).findByStudentNoAndProblemIdAndSourceType(any(), any(), any());
  }

  @Test
  void manualRetry_singleAcNotResolved() {
    WrongQuestionEntity row = persistedRow();
    when(repo.findById(7L)).thenReturn(Optional.of(row));

    RetryOutcomeDto outcome = service.manualRetry(
        STUDENT_NO, 7L,
        new WrongQuestionRetryRequest("ACCEPTED", "code", 10, 1024));

    assertEquals(1, outcome.newConsecutiveAcCount());
    assertFalse(outcome.justResolved());
    assertFalse(outcome.isResolved());
    ArgumentCaptor<WrongQuestionEntity> captor = ArgumentCaptor.forClass(WrongQuestionEntity.class);
    verify(repo).save(captor.capture());
    assertEquals(1, captor.getValue().getConsecutiveAcCount());
    assertFalse(captor.getValue().isResolved());
  }

  @Test
  void manualRetry_twoConsecutiveAcResolves() {
    WrongQuestionEntity row = persistedRow();
    row.setConsecutiveAcCount(1);
    when(repo.findById(7L)).thenReturn(Optional.of(row));

    RetryOutcomeDto outcome = service.manualRetry(
        STUDENT_NO, 7L,
        new WrongQuestionRetryRequest("AC", "code", 10, 1024));

    assertTrue(outcome.justResolved());
    assertTrue(outcome.isResolved());
    assertEquals(2, outcome.newConsecutiveAcCount());
    ArgumentCaptor<WrongQuestionEntity> captor = ArgumentCaptor.forClass(WrongQuestionEntity.class);
    verify(repo).save(captor.capture());
    assertTrue(captor.getValue().isResolved());
    assertNotNull(captor.getValue().getResolvedAt());
  }

  @Test
  void manualRetry_failureReopensResolvedRow() {
    WrongQuestionEntity row = persistedRow();
    row.setResolved(true);
    row.setResolvedAt(java.time.Instant.now());
    row.setConsecutiveAcCount(2);
    when(repo.findById(7L)).thenReturn(Optional.of(row));

    RetryOutcomeDto outcome = service.manualRetry(
        STUDENT_NO, 7L,
        new WrongQuestionRetryRequest("WRONG_ANSWER", "bad code", null, null));

    assertFalse(outcome.isResolved());
    ArgumentCaptor<WrongQuestionEntity> captor = ArgumentCaptor.forClass(WrongQuestionEntity.class);
    verify(repo).save(captor.capture());
    WrongQuestionEntity updated = captor.getValue();
    assertFalse(updated.isResolved());
    assertNull(updated.getResolvedAt());
    assertEquals(0, updated.getConsecutiveAcCount());
    assertEquals(2, updated.getTotalWrongCount()); // initial 1 + 1 miss
  }

  @Test
  void manualRetry_forbiddenForOtherStudent() {
    WrongQuestionEntity row = persistedRow(); // owned by STUDENT_NO
    when(repo.findById(7L)).thenReturn(Optional.of(row));

    ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.manualRetry(
        "99999999", 7L,
        new WrongQuestionRetryRequest("ACCEPTED", "code", null, null)));
    assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    verify(repo, never()).save(any());
  }

  @Test
  void uniqueConstraintAllowsTwoSources_keyFlattenedIntoSeparateLookups() {
    // Verifies that the service consults the repo with the source-type discriminator, so
    // the unique key (student_no, problem_id, source_type) can hold LEETCODE_PRACTICE and
    // PTA_SYNCED rows independently.
    when(repo.findByStudentNoAndProblemIdAndSourceType(
        eq(STUDENT_NO), eq(PROBLEM_ID), eq(WrongQuestionEntity.SourceType.LEETCODE_PRACTICE)))
        .thenReturn(Optional.empty());
    when(repo.findByStudentNoAndProblemIdAndSourceType(
        eq(STUDENT_NO), eq(PROBLEM_ID), eq(WrongQuestionEntity.SourceType.PTA_SYNCED)))
        .thenReturn(Optional.empty());
    when(tagLookupService.lookupTagsCsv(any())).thenReturn(null);

    service.recordSubmission(new RecordSubmissionCommand(
        STUDENT_NO, PROBLEM_ID, "Two Sum", "id:100", "Easy",
        "WRONG_ANSWER", "c1", null, null, null,
        WrongQuestionEntity.SourceType.LEETCODE_PRACTICE));
    service.recordSubmission(new RecordSubmissionCommand(
        STUDENT_NO, PROBLEM_ID, "Two Sum", "id:100", "Easy",
        "TIME_LIMIT_EXCEEDED", "c2", null, null, null,
        WrongQuestionEntity.SourceType.PTA_SYNCED));

    verify(repo, times(2)).save(any(WrongQuestionEntity.class));
    verify(logRepo, times(2)).save(any(WrongQuestionAttemptLogEntity.class));
  }

  private WrongQuestionEntity persistedRow() {
    WrongQuestionEntity row = new WrongQuestionEntity();
    row.setId(7L);
    row.setStudentNo(STUDENT_NO);
    row.setProblemId(PROBLEM_ID);
    row.setSourceType(WrongQuestionEntity.SourceType.LEETCODE_PRACTICE);
    row.setTotalWrongCount(1);
    row.setConsecutiveAcCount(0);
    return row;
  }
}
