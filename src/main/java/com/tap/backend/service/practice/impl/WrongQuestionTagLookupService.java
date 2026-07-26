package com.tap.backend.service.practice.impl;

import com.tap.backend.academic.dao.LeetCodeProblemTagDao;
import com.tap.backend.academic.entity.LeetCodeProblemTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads leetcode_problem_tag rows for a given problem and returns a comma-separated
 * tag CSV suitable for caching on the wrong_question_notebook row.
 *
 * Kept as a separate bean to avoid a circular dependency between the wrong-question
 * service and any higher-level orchestration that consumes it.
 */
@Service
public class WrongQuestionTagLookupService {

  private static final Logger log = LoggerFactory.getLogger(WrongQuestionTagLookupService.class);

  private final LeetCodeProblemTagDao tagDao;

  public WrongQuestionTagLookupService(LeetCodeProblemTagDao tagDao) {
    this.tagDao = tagDao;
  }

  public String lookupTagsCsv(Long problemId) {
    if (problemId == null) return null;
    try {
      List<LeetCodeProblemTag> rows = tagDao.findByProblemId(problemId);
      if (rows == null || rows.isEmpty()) return null;
      String csv = rows.stream()
          .map(LeetCodeProblemTag::getTagName)
          .filter(v -> v != null && !v.isBlank())
          .map(String::trim)
          .distinct()
          .collect(Collectors.joining(","));
      return csv.isEmpty() ? null : csv;
    } catch (Exception ex) {
      log.warn("Failed to load tags for problem {}: {}", problemId, ex.getMessage());
      return null;
    }
  }
}
