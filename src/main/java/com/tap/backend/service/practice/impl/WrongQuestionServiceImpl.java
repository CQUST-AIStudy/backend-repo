package com.tap.backend.service.practice.impl;

import com.tap.backend.domain.practice.WrongQuestionAttemptLogEntity;
import com.tap.backend.domain.practice.WrongQuestionAttemptLogEntity.AttemptLogSource;
import com.tap.backend.domain.practice.WrongQuestionEntity;
import com.tap.backend.dto.practice.AttemptLogDto;
import com.tap.backend.dto.practice.RecordSubmissionCommand;
import com.tap.backend.dto.practice.RetryOutcomeDto;
import com.tap.backend.dto.practice.WrongQuestionDetailDto;
import com.tap.backend.dto.practice.WrongQuestionFilter;
import com.tap.backend.dto.practice.WrongQuestionListItemDto;
import com.tap.backend.dto.practice.WrongQuestionRetryRequest;
import com.tap.backend.dto.practice.WrongQuestionStatsDto;
import com.tap.backend.repository.practice.WrongQuestionAttemptLogRepository;
import com.tap.backend.repository.practice.WrongQuestionRepository;
import com.tap.backend.service.practice.WrongQuestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class WrongQuestionServiceImpl implements WrongQuestionService {

  private static final Logger log = LoggerFactory.getLogger(WrongQuestionServiceImpl.class);
  private static final int RECENT_ATTEMPTS_PAGE_SIZE = 20;

  private final WrongQuestionRepository repo;
  private final WrongQuestionAttemptLogRepository logRepo;
  private final WrongQuestionTagLookupService tagLookupService;

  public WrongQuestionServiceImpl(WrongQuestionRepository repo,
                                   WrongQuestionAttemptLogRepository logRepo,
                                   WrongQuestionTagLookupService tagLookupService) {
    this.repo = repo;
    this.logRepo = logRepo;
    this.tagLookupService = tagLookupService;
  }

  @Override
  @Transactional
  public void recordSubmission(RecordSubmissionCommand cmd) {
    if (cmd == null) return;
    if (isAc(cmd.judgeStatus())) return; // AC submissions do not enter the notebook via this hook

    String category = ErrorCategoryUtil.classify(cmd.judgeStatus(), cmd.errorMessage());

    WrongQuestionEntity row = repo
        .findByStudentNoAndProblemIdAndSourceType(cmd.studentNo(), cmd.problemId(), cmd.sourceType())
        .map(existing -> {
          existing.setTotalWrongCount(existing.getTotalWrongCount() + 1);
          Instant now = Instant.now();
          existing.setLastWrongAt(now);
          existing.setLastAttemptAt(now);
          existing.setConsecutiveAcCount(0);
          existing.setResolved(false);
          existing.setResolvedAt(null);
          return existing;
        })
        .orElseGet(() -> {
          WrongQuestionEntity newborn = new WrongQuestionEntity();
          newborn.setStudentNo(cmd.studentNo());
          newborn.setProblemId(cmd.problemId());
          newborn.setProblemTitle(cmd.problemTitle());
          newborn.setProblemSlug(cmd.problemSlug());
          newborn.setDifficulty(cmd.difficulty());
          newborn.setSourceType(cmd.sourceType());
          newborn.setTotalWrongCount(1);
          Instant now = Instant.now();
          newborn.setFirstWrongAt(now);
          newborn.setLastWrongAt(now);
          newborn.setLastAttemptAt(now);
          newborn.setTagsCached(tagLookupService.lookupTagsCsv(cmd.problemId()));
          return newborn;
        });

    row.setLastJudgeStatus(cmd.judgeStatus());
    if (cmd.code() != null) row.setLastWrongCode(cmd.code());
    if (cmd.errorMessage() != null) row.setLastErrorMessage(cmd.errorMessage());
    row.setErrorCategory(category);
    repo.save(row);

    WrongQuestionAttemptLogEntity logRow = new WrongQuestionAttemptLogEntity();
    logRow.setNotebookId(row.getId());
    logRow.setJudgeStatus(cmd.judgeStatus());
    logRow.setWasAc(false);
    logRow.setCodeSnippet(cmd.code());
    logRow.setRuntimeMs(cmd.runtimeMs());
    logRow.setMemoryKb(cmd.memoryKb());
    logRow.setSource(AttemptLogSource.EXTERNAL_PRACTICE);
    logRepo.save(logRow);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<WrongQuestionListItemDto> list(String studentNo, WrongQuestionFilter filter, Pageable pageable) {
    WrongQuestionEntity.SourceType sourceType = parseSourceType(filter == null ? null : filter.sourceType());
    String resolvedFilter = filter == null ? null : filter.errorCategory();
    String difficulty = filter == null ? null : filter.difficulty();
    String tag = filter == null ? null : filter.tag();
    String q = filter == null ? null : filter.q();
    Boolean resolved = filter == null ? null : filter.resolved();

    return repo.filter(studentNo, resolved, sourceType, resolvedFilter, difficulty, tag, q, pageable)
        .map(this::toListItem);
  }

  @Override
  @Transactional(readOnly = true)
  public WrongQuestionDetailDto getDetail(String studentNo, Long notebookId) {
    WrongQuestionEntity row = requireOwnedRow(studentNo, notebookId);
    Page<WrongQuestionAttemptLogEntity> recent = logRepo.findByNotebookIdOrderByAttemptAtDesc(
        notebookId, PageRequest.of(0, RECENT_ATTEMPTS_PAGE_SIZE));
    List<AttemptLogDto> attempts = recent.getContent().stream()
        .map(a -> new AttemptLogDto(
            a.getAttemptAt(),
            a.getJudgeStatus(),
            a.isWasAc(),
            a.getRuntimeMs(),
            a.getMemoryKb(),
            a.getSource() == null ? null : a.getSource().name()))
        .toList();
    return new WrongQuestionDetailDto(
        row.getId(),
        row.getProblemId(),
        row.getProblemTitle(),
        row.getProblemSlug(),
        row.getDifficulty(),
        row.getSourceType() == null ? null : row.getSourceType().name(),
        row.getErrorCategory(),
        row.getTotalWrongCount(),
        row.getConsecutiveAcCount(),
        row.getFirstWrongAt(),
        row.getLastWrongAt(),
        row.getLastAttemptAt(),
        row.getLastJudgeStatus(),
        row.getLastWrongCode(),
        row.getLastErrorMessage(),
        row.isResolved(),
        row.getResolvedAt(),
        row.getTagsCached(),
        row.getNotes(),
        attempts
    );
  }

  @Override
  @Transactional(readOnly = true)
  public WrongQuestionStatsDto getStats(String studentNo) {
    long total = repo.countByStudentNo(studentNo);
    long resolved = repo.countByStudentNoAndResolved(studentNo, true);
    long unresolved = total - resolved;

    Map<String, Long> byDifficulty = new HashMap<>();
    for (Object[] row : repo.countByDifficulty(studentNo)) {
      Object k = row[0];
      Object v = row[1];
      byDifficulty.put(k == null ? null : k.toString(), asLong(v));
    }

    Map<String, Long> byErrorCategory = new HashMap<>();
    for (Object[] row : repo.countByErrorCategory(studentNo)) {
      Object k = row[0];
      Object v = row[1];
      byErrorCategory.put(k == null ? null : k.toString(), asLong(v));
    }

    return new WrongQuestionStatsDto(total, unresolved, resolved, byDifficulty, byErrorCategory);
  }

  @Override
  @Transactional
  public void updateNote(String studentNo, Long notebookId, String note) {
    WrongQuestionEntity row = requireOwnedRow(studentNo, notebookId);
    row.setNotes(note);
    repo.save(row);
  }

  @Override
  @Transactional
  public void removeSoftly(String studentNo, Long notebookId) {
    WrongQuestionEntity row = requireOwnedRow(studentNo, notebookId);
    row.setResolved(true);
    if (row.getResolvedAt() == null) row.setResolvedAt(Instant.now());
    if (row.getNotes() != null && row.getNotes().isBlank()) row.setNotes(null);
    repo.save(row);
  }

  @Override
  @Transactional
  public RetryOutcomeDto manualRetry(String studentNo, Long notebookId, WrongQuestionRetryRequest req) {
    if (req == null || req.judgeStatus() == null || req.judgeStatus().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "judgeStatus is required");
    }
    WrongQuestionEntity row = requireOwnedRow(studentNo, notebookId);

    boolean ac = isAc(req.judgeStatus());
    int nextStreak = ac ? row.getConsecutiveAcCount() + 1 : 0;
    boolean justResolved = false;

    Instant now = Instant.now();
    row.setConsecutiveAcCount(nextStreak);
    row.setLastAttemptAt(now);
    row.setLastJudgeStatus(req.judgeStatus());

    if (ac) {
      row.setLastWrongCode(null);
    } else {
      if (req.code() != null) row.setLastWrongCode(req.code());
      row.setTotalWrongCount(row.getTotalWrongCount() + 1);
      row.setLastWrongAt(now);
    }

    if (nextStreak >= 2 && !row.isResolved()) {
      row.setResolved(true);
      row.setResolvedAt(now);
      justResolved = true;
    } else if (!ac && row.isResolved()) {
      row.setResolved(false);
      row.setResolvedAt(null);
    }
    repo.save(row);

    WrongQuestionAttemptLogEntity logRow = new WrongQuestionAttemptLogEntity();
    logRow.setNotebookId(row.getId());
    logRow.setJudgeStatus(req.judgeStatus());
    logRow.setWasAc(ac);
    logRow.setCodeSnippet(req.code());
    logRow.setRuntimeMs(req.runtimeMs());
    logRow.setMemoryKb(req.memoryKb());
    logRow.setSource(AttemptLogSource.NOTEBOOK_PRACTICE);
    logRepo.save(logRow);

    return new RetryOutcomeDto(nextStreak, justResolved, row.isResolved());
  }

  private WrongQuestionEntity requireOwnedRow(String studentNo, Long notebookId) {
    WrongQuestionEntity row = repo.findById(notebookId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "wrong-question row not found"));
    if (!Objects.equals(row.getStudentNo(), studentNo)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "wrong-question row does not belong to caller");
    }
    return row;
  }

  private WrongQuestionListItemDto toListItem(WrongQuestionEntity row) {
    return new WrongQuestionListItemDto(
        row.getId(),
        row.getProblemId(),
        row.getProblemTitle(),
        row.getProblemSlug(),
        row.getDifficulty(),
        row.getSourceType() == null ? null : row.getSourceType().name(),
        row.getErrorCategory(),
        row.getTotalWrongCount(),
        row.getConsecutiveAcCount(),
        row.getLastWrongAt(),
        row.getLastAttemptAt(),
        row.isResolved(),
        row.getTagsCached(),
        row.getNotes()
    );
  }

  private static boolean isAc(String judgeStatus) {
    if (judgeStatus == null) return false;
    String s = judgeStatus.trim();
    if (s.isEmpty()) return false;
    return "ACCEPTED".equalsIgnoreCase(s) || "AC".equalsIgnoreCase(s);
  }

  private static WrongQuestionEntity.SourceType parseSourceType(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return WrongQuestionEntity.SourceType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static long asLong(Object v) {
    if (v == null) return 0L;
    if (v instanceof Number n) return n.longValue();
    try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0L; }
  }
}
