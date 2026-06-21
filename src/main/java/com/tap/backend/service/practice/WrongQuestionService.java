package com.tap.backend.service.practice;

import com.tap.backend.dto.practice.RecordSubmissionCommand;
import com.tap.backend.dto.practice.RetryOutcomeDto;
import com.tap.backend.dto.practice.WrongQuestionDetailDto;
import com.tap.backend.dto.practice.WrongQuestionFilter;
import com.tap.backend.dto.practice.WrongQuestionListItemDto;
import com.tap.backend.dto.practice.WrongQuestionRetryRequest;
import com.tap.backend.dto.practice.WrongQuestionStatsDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WrongQuestionService {

  /**
   * Submission hook for LeetCode practice and PTA sync pipelines.
   * AC submissions are treated as no-op (the retry endpoint handles streaks).
   */
  void recordSubmission(RecordSubmissionCommand cmd);

  Page<WrongQuestionListItemDto> list(String studentNo, WrongQuestionFilter filter, Pageable pageable);

  WrongQuestionDetailDto getDetail(String studentNo, Long notebookId);

  WrongQuestionStatsDto getStats(String studentNo);

  void updateNote(String studentNo, Long notebookId, String note);

  /**
   * Soft delete: marks the row as resolved and clears the note.
   */
  void removeSoftly(String studentNo, Long notebookId);

  /**
   * In-notebook retry. AC -> streak++, miss -> streak=0; streak>=2 marks resolved.
   * Re-opens the row if a miss arrives after resolution.
   */
  RetryOutcomeDto manualRetry(String studentNo, Long notebookId, WrongQuestionRetryRequest req);
}
