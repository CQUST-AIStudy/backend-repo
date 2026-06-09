package com.tap.backend.api;

import com.tap.common.api.ProblemResponse;
import com.tap.backend.quota.QuotaExceededException;
import com.tap.backend.service.InvalidClassPasswordException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

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

  @ExceptionHandler(InvalidClassPasswordException.class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  public ProblemResponse invalidClassPassword(InvalidClassPasswordException e) {
    return ProblemResponse.of("INVALID_CLASS_PASSWORD", e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ProblemResponse notFound(NoSuchElementException e) {
    return ProblemResponse.of("NOT_FOUND", e.getMessage());
  }

  @ExceptionHandler(SecurityException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ProblemResponse forbidden(SecurityException e) {
    return ProblemResponse.of("FORBIDDEN", e.getMessage());
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemResponse> responseStatus(ResponseStatusException e) {
    HttpStatusCode status = e.getStatusCode();
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ProblemResponse.of(statusCode(status), e.getReason() == null ? e.getMessage() : e.getReason()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
  public ProblemResponse unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
    return ProblemResponse.of("UNSUPPORTED_MEDIA_TYPE", e.getMessage());
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ProblemResponse missingRequestPart(MissingServletRequestPartException e) {
    return ProblemResponse.of("BAD_REQUEST", "missing request part: " + e.getRequestPartName());
  }

  @ExceptionHandler(QuotaExceededException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public ProblemResponse quota(QuotaExceededException e) {
    return ProblemResponse.of("QUOTA_EXCEEDED", e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ProblemResponse serverError(Exception e) {
    return ProblemResponse.of("INTERNAL_ERROR", e.getMessage());
  }

  private String statusCode(HttpStatusCode status) {
    if (status instanceof HttpStatus httpStatus) {
      return httpStatus.name();
    }
    return "HTTP_" + status.value();
  }
}
