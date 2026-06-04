package com.tap.backend.api.users;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.service.TeachingClassService;
import com.tap.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TeachingClassService teachingClassService;

  public UserManagementController(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      TeachingClassService teachingClassService
  ) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.teachingClassService = teachingClassService;
  }

  public record UserResponse(
      Long id,
      String username,
      String displayName,
      String role,
      Boolean enabled,
      Instant createdAt,
      Instant updatedAt
  ) {}

  public record CreateUserRequest(
      @NotBlank @Size(max = 64) String username,
      @Size(max = 128) String displayName,
      @NotBlank @Size(max = 16) String role,
      @NotBlank @Size(min = 6, max = 128) String password,
      Boolean enabled
  ) {}

  public record UpdateUserRequest(
      @Size(max = 128) String displayName,
      @NotBlank @Size(max = 16) String role
  ) {}

  public record StatusRequest(@NotNull Boolean enabled) {}

  public record PasswordRequest(@NotBlank @Size(min = 6, max = 128) String password) {}

  @GetMapping
  public ApiResponse<List<UserResponse>> list() {
    return ApiResponse.of(userRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toResponse)
        .toList());
  }

  @PostMapping
  public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
    String username = normalizeUsername(req.username());
    if (userRepository.existsByUsername(username)) {
      throw new IllegalArgumentException("username already exists");
    }

    UserEntity user = new UserEntity();
    user.setUsername(username);
    user.setDisplayName(blankToNull(req.displayName()));
    user.setRole(parseRole(req.role()));
    user.setPasswordHash(passwordEncoder.encode(req.password()));
    user.setEnabled(req.enabled() == null || req.enabled());
    user = userRepository.save(user);
    if (user.getRole() == UserRole.STUDENT && Boolean.TRUE.equals(user.getEnabled())) {
      teachingClassService.bindStudentAccountByStudentNum(user.getId(), user.getUsername());
    }
    return ApiResponse.of(toResponse(user));
  }

  @PutMapping("/{id}")
  public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
    UserEntity user = requireUser(id);
    user.setDisplayName(blankToNull(req.displayName()));
    user.setRole(parseRole(req.role()));
    return ApiResponse.of(toResponse(userRepository.save(user)));
  }

  @PatchMapping("/{id}/status")
  public ApiResponse<UserResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
    UserEntity user = requireUser(id);
    user.setEnabled(req.enabled());
    return ApiResponse.of(toResponse(userRepository.save(user)));
  }

  @PatchMapping("/{id}/password")
  public ApiResponse<UserResponse> updatePassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest req) {
    UserEntity user = requireUser(id);
    user.setPasswordHash(passwordEncoder.encode(req.password()));
    return ApiResponse.of(toResponse(userRepository.save(user)));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    UserEntity user = requireUser(id);
    userRepository.delete(user);
    return ApiResponse.of(null);
  }

  private UserEntity requireUser(Long id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("user not found"));
  }

  private UserResponse toResponse(UserEntity user) {
    return new UserResponse(
        user.getId(),
        user.getUsername(),
        user.getDisplayName(),
        user.getRole().name(),
        Boolean.TRUE.equals(user.getEnabled()),
        user.getCreatedAt(),
        user.getUpdatedAt());
  }

  private UserRole parseRole(String role) {
    try {
      return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
    } catch (Exception e) {
      throw new IllegalArgumentException("unsupported role: " + role);
    }
  }

  private String normalizeUsername(String username) {
    String normalized = username == null ? "" : username.trim();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("username is required");
    }
    return normalized;
  }

  private String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
