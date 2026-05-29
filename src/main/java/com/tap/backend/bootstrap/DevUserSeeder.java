package com.tap.backend.bootstrap;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seeds default dev users (admin, teacher1) for local development.
 * Only active when the "dev" profile is enabled — never runs in tests or production.
 */
@Component
@Profile("dev")
public class DevUserSeeder implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);
  private static final String DEFAULT_ADMIN_PASSWORD = "admin123";
  private static final String DEFAULT_TEACHER_PASSWORD = "teacher123";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final boolean enabled;
  private final String adminPassword;
  private final String teacherPassword;

  public DevUserSeeder(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${TAP_DEV_SEED_USERS_ENABLED:false}") boolean enabled,
      @Value("${TAP_DEV_ADMIN_PASSWORD:" + DEFAULT_ADMIN_PASSWORD + "}") String adminPassword,
      @Value("${TAP_DEV_TEACHER_PASSWORD:" + DEFAULT_TEACHER_PASSWORD + "}") String teacherPassword) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.enabled = enabled;
    this.adminPassword = adminPassword;
    this.teacherPassword = teacherPassword;
  }

  @Override
  public void run(String... args) {
    if (!enabled) {
      log.info("Skipping TAP dev user seeding because TAP_DEV_SEED_USERS_ENABLED=false");
      return;
    }
    if (DEFAULT_ADMIN_PASSWORD.equals(adminPassword)) {
      log.error("✖ Refusing to seed admin user with default password. Set TAP_DEV_ADMIN_PASSWORD env var.");
      return;
    }
    if (DEFAULT_TEACHER_PASSWORD.equals(teacherPassword)) {
      log.error("✖ Refusing to seed teacher user with default password. Set TAP_DEV_TEACHER_PASSWORD env var.");
      return;
    }
    seed("admin", adminPassword, UserRole.ADMIN);
    seed("teacher1", teacherPassword, UserRole.TEACHER);
    log.info("Dev user seeding complete (admin, teacher1)");
  }

  private void seed(String username, String password, UserRole role) {
    UserEntity user = userRepository.findByUsername(username).orElseGet(() -> {
      UserEntity u = new UserEntity();
      u.setUsername(username);
      u.setDisplayName(username);
      return u;
    });
    user.setRole(role);
    if (user.getPasswordHash() == null
        || user.getPasswordHash().isBlank()
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
      user.setPasswordHash(passwordEncoder.encode(password));
    }
    userRepository.save(user);
  }

  private void warnIfUsingDefaultPassword(String username, String actualPassword, String defaultPassword) {
    if (defaultPassword.equals(actualPassword)) {
      log.warn("TAP dev seed user '{}' is still using the default seeded password", username);
    }
  }
}
