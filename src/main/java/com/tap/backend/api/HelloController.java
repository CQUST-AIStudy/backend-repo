package com.tap.backend.api;

import com.tap.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {
  @GetMapping("/hello")
  public ApiResponse<String> hello() {
    return ApiResponse.of("tap-backend ok");
  }
}
