package com.tap.backend.api.users;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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
      String email,
      String phone,
      String usernum,
      String classname,
      String department,
      Boolean enabled,
      Instant createdAt,
      Instant updatedAt
  ) {}

  public static class CreateUserRequest {
    @NotBlank @Size(max = 64)
    private String username;
    @Size(max = 128)
    private String displayName;
    @NotBlank @Size(max = 16)
    private String role;
    @NotBlank @Size(min = 6, max = 128)
    private String password;
    @Size(max = 128) private String email;
    @Size(max = 20) private String phone;
    @Size(max = 64) private String usernum;
    @Size(max = 128) private String classname;
    @Size(max = 100) private String department;
    private Boolean enabled;

    // Alias: frontend sends "name" instead of "username"
    @JsonProperty("name")
    public void setName(String name) { this.username = name; this.displayName = (this.displayName == null ? name : this.displayName); }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    @JsonProperty("id") public void setId(String id) { this.usernum = id; }
    public String getUsernum() { return usernum; }
    public void setUsernum(String usernum) { this.usernum = usernum; }
    @JsonProperty("class") public void setClass_(String c) { this.classname = c; }
    @JsonProperty("className") public void setClassName(String c) { this.classname = c; }
    public String getClassname() { return classname; }
    public void setClassname(String classname) { this.classname = classname; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  }

  public static class UpdateUserRequest {
    private String username;
    private String displayName;
    private String role;
    private String password;
    private String email;
    private String phone;
    private String usernum;
    private String classname;
    private String department;
    private String grade;
    private String title;
    private Boolean enabled;
    private String status;

    @JsonProperty("name") public void setName(String name) { this.username = name; this.displayName = (this.displayName == null ? name : this.displayName); }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    @JsonProperty("id") public void setId(String id) { this.usernum = id; }
    public String getUsernum() { return usernum; }
    public void setUsernum(String usernum) { this.usernum = usernum; }
    @JsonProperty("class") public void setClass_(String c) { this.classname = c; }
    @JsonProperty("className") public void setClassName(String c) { this.classname = c; }
    public String getClassname() { return classname; }
    public void setClassname(String classname) { this.classname = classname; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    @JsonProperty("status") public void setStatus(String status) { this.status = status; }
    public String getStatus() { return status; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  }

  @GetMapping
  public ApiResponse<List<UserResponse>> list() {
    return ApiResponse.of(userRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toResponse)
        .toList());
  }

  @PostMapping
  public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
    String username = normalizeUsername(req.getUsername());
    if (userRepository.existsByUsername(username)) {
      throw new IllegalArgumentException("username already exists");
    }
    UserEntity user = new UserEntity();
    user.setUsername(username);
    user.setDisplayName(req.getDisplayName() != null ? req.getDisplayName().trim() : username);
    user.setRole(parseRole(req.getRole()));
    user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
    user.setEmail(req.getEmail() != null ? req.getEmail().trim() : null);
    user.setPhone(req.getPhone() != null ? req.getPhone().trim() : null);
    user.setUsernum(req.getUsernum() != null ? req.getUsernum().trim() : null);
    user.setClassname(req.getClassname() != null ? req.getClassname().trim() : null);
    user.setDepartment(req.getDepartment() != null ? req.getDepartment().trim() : null);
    user.setEnabled(req.getEnabled() == null || req.getEnabled());
    user = userRepository.save(user);
    if (user.getRole() == UserRole.STUDENT && Boolean.TRUE.equals(user.getEnabled())) {
      teachingClassService.bindStudentAccountByStudentNum(user.getId(), user.getUsername());
    }
    return ApiResponse.of(toResponse(user));
  }

  @PutMapping("/{id}")
  public ApiResponse<UserResponse> update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
    UserEntity user = requireUser(id);
    if (req.getUsername() != null && !req.getUsername().isBlank()) {
      user.setUsername(req.getUsername().trim());
    }
    if (req.getDisplayName() != null) {
      user.setDisplayName(req.getDisplayName().isBlank() ? null : req.getDisplayName().trim());
    }
    if (req.getRole() != null && !req.getRole().isBlank()) {
      user.setRole(parseRole(req.getRole()));
    }
    if (req.getPassword() != null && !req.getPassword().isBlank()) {
      user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
    }
    if (req.getEmail() != null) {
      user.setEmail(req.getEmail().isBlank() ? null : req.getEmail().trim());
    }
    if (req.getPhone() != null) {
      user.setPhone(req.getPhone().isBlank() ? null : req.getPhone().trim());
    }
    if (req.getUsernum() != null) {
      user.setUsernum(req.getUsernum().isBlank() ? null : req.getUsernum().trim());
    }
    if (req.getClassname() != null) {
      user.setClassname(req.getClassname().isBlank() ? null : req.getClassname().trim());
    }
    if (req.getDepartment() != null) {
      user.setDepartment(req.getDepartment().isBlank() ? null : req.getDepartment().trim());
    }
    if (req.getEnabled() != null) {
      user.setEnabled(req.getEnabled());
    } else if (req.getStatus() != null && !req.getStatus().isBlank()) {
      user.setEnabled("active".equalsIgnoreCase(req.getStatus().trim()));
    }
    return ApiResponse.of(toResponse(userRepository.save(user)));
  }

  @PatchMapping("/{id}/password")
  public ApiResponse<UserResponse> updatePassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest req) {
    UserEntity user = requireUser(id);
    user.setPasswordHash(passwordEncoder.encode(req.password()));
    return ApiResponse.of(toResponse(userRepository.save(user)));
  }

  @PatchMapping("/{id}/status")
  public ApiResponse<UserResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
    UserEntity user = requireUser(id);
    user.setEnabled(req.enabled());
    return ApiResponse.of(toResponse(userRepository.save(user)));
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    UserEntity user = requireUser(id);
    userRepository.delete(user);
    return ApiResponse.of(null);
  }

  public record PasswordRequest(@NotBlank @Size(min = 6, max = 128) String password) {}
  public record StatusRequest(@NotNull Boolean enabled) {}

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
        user.getEmail(),
        user.getPhone(),
        user.getUsernum(),
        user.getClassname(),
        user.getDepartment(),
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
    if (normalized.isBlank()) throw new IllegalArgumentException("username is required");
    return normalized;
  }
}
