package com.tap.backend.api.auth;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.service.ErrorAnalysisService;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.JwtService;
import com.tap.backend.service.TeachingClassService;
import com.tap.common.api.ApiResponse;
import com.tap.common.api.Maps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final LegacySessionAccessResolver legacySessionAccessResolver;
  private final TeachingClassService teachingClassService;
  private final ErrorAnalysisService errorAnalysisService;

  public AuthController(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      LegacySessionAccessResolver legacySessionAccessResolver,
      TeachingClassService teachingClassService,
      ErrorAnalysisService errorAnalysisService
  ) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.legacySessionAccessResolver = legacySessionAccessResolver;
    this.teachingClassService = teachingClassService;
    this.errorAnalysisService = errorAnalysisService;
  }

  public record LoginRequest(
      @NotBlank @Size(max = 64) String username,
      @NotBlank @Size(max = 128) String password,
      @Size(max = 32) String role
  ) {}

  @PostMapping("/login")
  public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
    UserEntity user = userRepository.findByUsername(req.username())
        .orElseThrow(() -> unauthorized("invalid username or password"));
    if (!Boolean.TRUE.equals(user.getEnabled())) {
      throw unauthorized("user account is disabled");
    }
    if (user.getPasswordHash() == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
      throw unauthorized("invalid username or password");
    }
    requireRequestedRole(user.getRole(), req.role());
    bindStudentRosterIfNeeded(user, user.getUsername());
    // 学生登录后异步扫描进行中的实验，检测预警
    if (user.getRole() == UserRole.STUDENT) {
      String studentNo = (user.getUsernum() != null && !user.getUsernum().isBlank())
              ? user.getUsernum().trim() : user.getUsername();
      errorAnalysisService.scanActiveExperimentsAndWarn(
          studentNo,
          user.getDisplayName() != null && !user.getDisplayName().isBlank()
              ? user.getDisplayName() : user.getUsername());
    }
    String token = jwtService.issue(user);
    return ApiResponse.of(Maps.of(
        "accessToken", token,
        "tokenType", "Bearer",
        "role", user.getRole().name(),
        "userId", user.getId()
    ));
  }

  @PostMapping("/session")
  public ApiResponse<Map<String, Object>> issueFromLegacySession(HttpServletRequest request) {
    com.tap.backend.academic.entity.UserEntity legacyUser = legacySessionAccessResolver.requireAuthenticated(request);
    UserRole role = mapRole(legacyUser.getRole());
    UserEntity tapUser = userRepository.findByUsername(legacyUser.getUsername())
        .orElseGet(UserEntity::new);

    tapUser.setUsername(legacyUser.getUsername());
    tapUser.setDisplayName(resolveDisplayName(legacyUser));
    tapUser.setRole(role);
    tapUser = userRepository.save(tapUser);
    bindStudentRosterIfNeeded(tapUser, resolveLegacyStudentNum(legacyUser));

    String token = jwtService.issue(tapUser);
    return ApiResponse.of(Maps.of(
        "accessToken", token,
        "tokenType", "Bearer",
        "role", tapUser.getRole().name(),
        "userId", tapUser.getId()
    ));
  }

  private String resolveDisplayName(com.tap.backend.academic.entity.UserEntity legacyUser) {
    if (legacyUser.getUsername() == null || legacyUser.getUsername().isBlank()) {
      return "user";
    }
    return legacyUser.getUsername();
  }

  private String resolveLegacyStudentNum(com.tap.backend.academic.entity.UserEntity legacyUser) {
    if (legacyUser.getUsernum() != null && !legacyUser.getUsernum().isBlank()) {
      return legacyUser.getUsernum();
    }
    return legacyUser.getUsername();
  }

  private void bindStudentRosterIfNeeded(UserEntity user, String studentNum) {
    if (user != null && user.getRole() == UserRole.STUDENT) {
      teachingClassService.bindStudentAccountByStudentNum(user.getId(), studentNum);
    }
  }

  private void requireRequestedRole(UserRole actualRole, String requestedRole) {
    UserRole expectedRole = parseRequestedRole(requestedRole);
    if (expectedRole != null && actualRole != expectedRole) {
      throw unauthorized(roleMismatchMessage(expectedRole));
    }
  }

  private UserRole parseRequestedRole(String requestedRole) {
    if (requestedRole == null || requestedRole.isBlank()) {
      return null;
    }
    return switch (requestedRole.trim().toUpperCase(Locale.ROOT)) {
      case "ADMIN" -> UserRole.ADMIN;
      case "TEACHER" -> UserRole.TEACHER;
      case "STUDENT" -> UserRole.STUDENT;
      default -> throw new IllegalArgumentException("unsupported login role: " + requestedRole);
    };
  }

  private String roleMismatchMessage(UserRole expectedRole) {
    return switch (expectedRole) {
      case ADMIN -> "该账号不是管理员账号，请切换正确身份登录";
      case TEACHER -> "该账号不是教师账号，请切换正确身份登录";
      case STUDENT -> "该账号不是学生账号，请切换正确身份登录";
    };
  }

  private UserRole mapRole(String legacyRole) {
    if (legacyRole == null) {
      throw new IllegalArgumentException("unsupported legacy role for tap jwt exchange");
    }
    String normalized = legacyRole.trim().toUpperCase();
    return switch (normalized) {
      case "ADMIN" -> UserRole.ADMIN;
      case "TEACHER" -> UserRole.TEACHER;
      case "STUDENT" -> UserRole.STUDENT;
      default -> throw new IllegalArgumentException("unsupported legacy role for tap jwt exchange");
    };
  }

  private ResponseStatusException unauthorized(String message) {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
  }
}
