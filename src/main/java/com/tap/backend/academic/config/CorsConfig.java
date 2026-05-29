package com.tap.backend.academic.config;

// CORS 已由 com.tap.backend.security.SecurityConfig 统一管理
// 此类保留但不再注册 CORS 映射，避免与 Spring Security CORS 冲突

import org.springframework.context.annotation.Configuration;

@Configuration
public class CorsConfig {
    // intentionally empty — CORS handled by SecurityConfig.corsConfigurationSource()
}
