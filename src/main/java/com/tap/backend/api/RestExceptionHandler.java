package com.tap.backend.api;

import com.tap.common.api.ProblemResponse;
import com.tap.backend.quota.QuotaExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class RestExceptionHandler {
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
  public ProblemResponse payloadTooLarge(MaxUploadSizeExceededException e) {
    return ProblemResponse.of("PAYLOAD_TOO_LARGE", "上传文件过大，当前单次上传上限为 512MB，请压缩后重试或分批上传");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ProblemResponse badRequest(IllegalArgumentException e) {
    return ProblemResponse.of("BAD_REQUEST", e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ProblemResponse serverError(Exception e) {
    return ProblemResponse.of("INTERNAL_ERROR", e.getMessage());
  }

  @ExceptionHandler(QuotaExceededException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public ProblemResponse quota(QuotaExceededException e) {
    return ProblemResponse.of("QUOTA_EXCEEDED", e.getMessage());
  }
}
