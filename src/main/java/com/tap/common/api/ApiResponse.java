package com.tap.common.api;

public record ApiResponse<T>(T data) {
  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data);
  }
}
