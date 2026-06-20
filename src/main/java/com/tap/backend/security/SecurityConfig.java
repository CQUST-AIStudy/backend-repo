package com.tap.backend.security;

import com.tap.backend.academic.security.LegacySessionAuthFilter;
import com.tap.backend.domain.user.UserRole;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Value("${CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*,http://127.0.0.1:*,http://172.21.11.128:8080,http://47.108.176.134:8090}")
  private String corsAllowedOriginPatterns;

  private static final String[] TAP_API_MATCHERS = {
      "/api/auth/**",
      "/api/documents/**",
      "/api/uploads/**",
      "/api/papers/**",
      "/api/tap-chat",
      "/api/tap-chat/**",
      "/api/agent/**",
      "/api/zip-organize/**",
      "/api/admin/**",
      "/api/hello",
      "/actuator/**",
      "/api/classes/**",
      "/api/student-classes/**",
      "/api/grading/**",
      "/api/pta-cookie/**",
      "/api/teachers/**",
      "/api/users/**",
      "/api/analytics/**"
  };

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * TAP API 路径使用 JWT 认证 (stateless)
   * 匹配 /api/auth/**, /api/documents/**, /api/uploads/**, /api/papers/**,
   *       /api/chat, /api/agent/**, /api/admin/**, /api/hello, /actuator/**
   */
  /**
   * TAP API chain — JWT-only, fully stateless.
   * No legacy session fallback; TAP clients must use Bearer tokens.
   */
  @Bean
  @Order(1)
  public SecurityFilterChain tapSecurityFilterChain(
      HttpSecurity http,
      JwtAuthFilter jwtAuthFilter,
      LegacySessionAuthFilter legacySessionAuthFilter) throws Exception {
    http.securityMatcher(TAP_API_MATCHERS);
    http.csrf(csrf -> csrf.disable());
    http.cors(cors -> {});
    http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }));
    http.authorizeHttpRequests(auth -> auth
        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
        .requestMatchers("/actuator/health", "/api/auth/login", "/api/auth/session").permitAll()
        .requestMatchers("/api/admin/**").hasRole(UserRole.ADMIN.name())
        .requestMatchers("/api/documents/**").authenticated()
        .requestMatchers("/api/uploads/**").authenticated()
        .requestMatchers("/api/papers/**").authenticated()
        .requestMatchers("/api/tap-chat", "/api/tap-chat/**").authenticated()
        .requestMatchers("/api/agent/**").authenticated()
        .requestMatchers("/api/zip-organize/**").authenticated()
        .requestMatchers(HttpMethod.POST, "/api/classes/join").permitAll()
        // The student class page still uses the legacy session cookie. The controller
        // validates either JWT or a legacy STUDENT session to avoid frontend changes.
        .requestMatchers("/api/student-classes/**").permitAll()
        .requestMatchers(HttpMethod.PUT, "/api/classes/*/pta-sync/callback").permitAll()
        .requestMatchers(HttpMethod.PUT, "/api/pta-cookie/status").permitAll()
        .requestMatchers("/api/pta-cookie/**").hasAnyRole(UserRole.TEACHER.name(), UserRole.ADMIN.name())
        .requestMatchers("/api/teachers/**").hasAnyRole(UserRole.TEACHER.name(), UserRole.ADMIN.name())
        .requestMatchers("/api/users/**").hasRole(UserRole.ADMIN.name())
        .requestMatchers("/api/grading/**").hasAnyRole(UserRole.TEACHER.name(), UserRole.ADMIN.name())
        .requestMatchers("/api/classes/**").hasAnyRole(UserRole.TEACHER.name(), UserRole.ADMIN.name())
        .requestMatchers("/api/analytics/student/**").hasRole(UserRole.STUDENT.name())
        .requestMatchers("/api/student/animation-explain/**").hasRole(UserRole.STUDENT.name())
        .requestMatchers("/api/analytics/**").hasAnyRole(UserRole.TEACHER.name(), UserRole.ADMIN.name())
        .anyRequest().authenticated()
    );
    http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(legacySessionAuthFilter, JwtAuthFilter.class);
    return http.build();
  }

  /**
   * Legacy AI_Ds chain — session-based auth managed by the original LoginController.
   * Covers all paths not matched by the TAP API chain above.
   */
  @Bean
  @Order(2)
  public SecurityFilterChain aiDsSecurityFilterChain(
      HttpSecurity http,
      JwtAuthFilter jwtAuthFilter,
      LegacySessionAuthFilter legacySessionAuthFilter)
      throws Exception {
    http.securityMatcher("/**");
    http.csrf(csrf -> csrf.disable());
    http.cors(cors -> {});
    http.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }));
    http.authorizeHttpRequests(auth -> auth
        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        .requestMatchers(HttpMethod.POST, "/api/login", "/api/register", "/logout", "/api/logout").permitAll()
        .anyRequest().authenticated()
    );
    http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(legacySessionAuthFilter, JwtAuthFilter.class);
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(parseCsv(corsAllowedOriginPatterns));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of("X-Trace-Id"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  private List<String> parseCsv(String raw) {
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
