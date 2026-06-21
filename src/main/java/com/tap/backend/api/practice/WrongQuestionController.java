package com.tap.backend.api.practice;

import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.common.api.ApiResponse;
import com.tap.backend.dto.practice.RecordSubmissionDto;
import com.tap.backend.dto.practice.RetryOutcomeDto;
import com.tap.backend.dto.practice.WrongQuestionDetailDto;
import com.tap.backend.dto.practice.WrongQuestionFilter;
import com.tap.backend.dto.practice.WrongQuestionListItemDto;
import com.tap.backend.dto.practice.WrongQuestionNoteUpdateRequest;
import com.tap.backend.dto.practice.WrongQuestionRetryRequest;
import com.tap.backend.dto.practice.WrongQuestionStatsDto;
import com.tap.backend.service.practice.WrongQuestionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/wrong-questions")
public class WrongQuestionController {

  private final WrongQuestionService service;
  private final StudentSessionResolver studentSessionResolver;

  public WrongQuestionController(WrongQuestionService service,
                                  StudentSessionResolver studentSessionResolver) {
    this.service = service;
    this.studentSessionResolver = studentSessionResolver;
  }

  @GetMapping
  public ApiResponse<Page<WrongQuestionListItemDto>> list(
      WrongQuestionFilter filter,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "lastWrongAt") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
    Sort sortObj = Sort.by(new Sort.Order(dir, sanitizeSort(sort)));
    PageRequest pageRequest = PageRequest.of(Math.max(0, page), clampSize(size), sortObj);
    return ApiResponse.of(service.list(studentNo, filter, pageRequest));
  }

  @GetMapping("/stats")
  public ApiResponse<WrongQuestionStatsDto> stats(HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    return ApiResponse.of(service.getStats(studentNo));
  }

  @GetMapping("/{id}")
  public ApiResponse<WrongQuestionDetailDto> detail(@PathVariable Long id, HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    return ApiResponse.of(service.getDetail(studentNo, id));
  }

  @PatchMapping("/{id}")
  public ApiResponse<Void> updateNote(@PathVariable Long id,
                                       @RequestBody WrongQuestionNoteUpdateRequest body,
                                       HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    service.updateNote(studentNo, id, body == null ? null : body.note());
    return ApiResponse.of(null);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> remove(@PathVariable Long id, HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    service.removeSoftly(studentNo, id);
    return ApiResponse.of(null);
  }

  @PostMapping("/{id}/retry")
  public ApiResponse<RetryOutcomeDto> retry(@PathVariable Long id,
                                             @RequestBody WrongQuestionRetryRequest body,
                                             HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    return ApiResponse.of(service.manualRetry(studentNo, id, body));
  }

  /**
   * Internal hook for front-end callers (or future server-side callers) that want to
   * record a submission into the notebook without going through the LeetCode submit
   * pipeline. AC submissions are treated as no-op by the service.
   */
  @PostMapping("/record")
  public ApiResponse<Void> record(@RequestBody RecordSubmissionDto body, HttpServletRequest request) {
    String studentNo = studentSessionResolver.requireStudentId(request);
    service.recordSubmission(body == null ? null : body.toCommand(studentNo));
    return ApiResponse.of(null);
  }

  private static String sanitizeSort(String raw) {
    if (raw == null || raw.isBlank()) return "lastWrongAt";
    String trimmed = raw.trim();
    switch (trimmed) {
      case "lastWrongAt":
      case "lastAttemptAt":
      case "firstWrongAt":
      case "totalWrongCount":
      case "consecutiveAcCount":
      case "difficulty":
      case "problemTitle":
        return trimmed;
      default:
        return "lastWrongAt";
    }
  }

  private static int clampSize(int size) {
    if (size < 1) return 20;
    if (size > 100) return 100;
    return size;
  }
}
