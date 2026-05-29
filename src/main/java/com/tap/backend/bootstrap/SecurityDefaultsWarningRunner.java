package com.tap.backend.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

@Component
public class SecurityDefaultsWarningRunner implements CommandLineRunner {
  private static final Logger log = LoggerFactory.getLogger(SecurityDefaultsWarningRunner.class);
  private static final String KNOWN_WEAK_DB_PASSWORD = "123456";
  private static final String KNOWN_WEAK_JWT_SECRET =
      "CHANGE_ME_PLEASE_CHANGE_ME_32_BYTES_MINIMUM_123456";
  private static final String DEFAULT_MINIO_CREDENTIAL = "minioadmin";
  private static final int MIN_JWT_SECRET_LENGTH = 32;

  private final Environment environment;

  public SecurityDefaultsWarningRunner(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void run(String... args) {
    boolean isDevProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev")
        || environment.getActiveProfiles().length == 0;

    List<String> fatal = new ArrayList<>();

    checkDbPassword(fatal);
    checkJwtSecret(fatal);
    checkDefaultMinioCredentials();
    checkAiProviderMock();
    checkTranslationFallback();
    checkSchemaManagement();

    if (!fatal.isEmpty()) {
      String joined = String.join("; ", fatal);
      if (!isDevProfile) {
        throw new IllegalStateException(
            "FATAL: Critical security configuration errors in non-dev profile: " + joined);
      }
      log.error("⚠ Security configuration issues detected (dev profile tolerates but MUST be fixed before deployment): {}", joined);
    }
  }

  private void checkDbPassword(List<String> fatal) {
    String dbPassword = environment.getProperty("spring.datasource.password", "");
    if (dbPassword.isBlank()) {
      fatal.add("DB_PASSWORD is blank — set DB_PASSWORD or DB_PASS environment variable");
    } else if (KNOWN_WEAK_DB_PASSWORD.equals(dbPassword)) {
      fatal.add("DB_PASSWORD is using the known weak value '123456'");
    }
  }

  private void checkJwtSecret(List<String> fatal) {
    String jwtSecret = environment.getProperty("tap.security.jwt.secret", "");
    if (jwtSecret.isBlank()) {
      fatal.add("JWT_SECRET is blank — set JWT_SECRET environment variable");
    } else if (KNOWN_WEAK_JWT_SECRET.equals(jwtSecret)) {
      fatal.add("JWT_SECRET is using the known weak placeholder value");
    } else if (jwtSecret.length() < MIN_JWT_SECRET_LENGTH) {
      fatal.add("JWT_SECRET is too short (minimum " + MIN_JWT_SECRET_LENGTH + " characters)");
    }
  }

  private void checkDefaultMinioCredentials() {
    String accessKey = environment.getProperty("tap.storage.s3.access-key", "");
    String secretKey = environment.getProperty("tap.storage.s3.secret-key", "");
    if (DEFAULT_MINIO_CREDENTIAL.equals(accessKey) || DEFAULT_MINIO_CREDENTIAL.equals(secretKey)) {
      log.warn("⚠ MinIO credentials are still using default values; set MINIO_ACCESS_KEY and MINIO_SECRET_KEY");
    }
  }

  private void checkAiProviderMock() {
    String provider = environment.getProperty("tap.ai.provider", "mock");
    if ("mock".equalsIgnoreCase(provider)) {
      log.warn("⚠ AI provider is running in mock mode; set AI_PROVIDER=openai for real model responses");
    }
  }

  private void checkTranslationFallback() {
    String provider = environment.getProperty("tap.translation.provider", "deepl");
    String deeplKey = environment.getProperty("tap.translation.deepl.api-key", "");
    if ("deepl".equalsIgnoreCase(provider) && (deeplKey == null || deeplKey.isBlank())) {
      log.warn("⚠ Translation is configured for DeepL but no API key is set; runtime will fall back to mock translation");
    }
  }

  private void checkSchemaManagement() {
    boolean flywayEnabled = environment.getProperty("spring.flyway.enabled", Boolean.class, false);
    String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto", "");
    if (flywayEnabled && !"validate".equalsIgnoreCase(ddlAuto) && !"none".equalsIgnoreCase(ddlAuto)) {
      log.warn("⚠ Flyway is enabled but hibernate ddl-auto='{}'; recommend 'validate' or 'none' to let Flyway own schema", ddlAuto);
    }
  }
}
