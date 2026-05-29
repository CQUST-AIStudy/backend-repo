package com.tap.backend.service;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.infra.crypto.AesGcmTextEncryptor;
import com.tap.backend.repo.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherPtaCredentialService {
  private final UserRepository userRepository;
  private final AesGcmTextEncryptor textEncryptor;

  public TeacherPtaCredentialService(
      UserRepository userRepository,
      AesGcmTextEncryptor textEncryptor) {
    this.userRepository = userRepository;
    this.textEncryptor = textEncryptor;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getCredentialSummary(long teacherId) {
    return toSummaryMap(requireTeacherUser(teacherId));
  }

  @Transactional
  public Map<String, Object> saveCredentials(long teacherId, String ptaUsername, String ptaPassword) {
    String username = ptaUsername == null ? "" : ptaUsername.trim();
    String password = ptaPassword == null ? "" : ptaPassword;
    if (username.isBlank() || password.isBlank()) {
      throw new IllegalArgumentException("pta username and password are required");
    }

    UserEntity user = requireTeacherUser(teacherId);
    user.setPtaUsername(username);
    user.setPtaPasswordCiphertext(textEncryptor.encrypt(password));
    userRepository.save(user);
    return toSummaryMap(user);
  }

  @Transactional
  public Map<String, Object> clearCredentials(long teacherId) {
    UserEntity user = requireTeacherUser(teacherId);
    user.setPtaUsername(null);
    user.setPtaPasswordCiphertext(null);
    userRepository.save(user);
    return toSummaryMap(user);
  }

  @Transactional(readOnly = true)
  public ResolvedPtaCredential resolveCredentials(long teacherId) {
    UserEntity user = requireTeacherUser(teacherId);
    if (!hasStoredCredential(user)) {
      return null;
    }
    return new ResolvedPtaCredential(
        user.getPtaUsername().trim(),
        textEncryptor.decrypt(user.getPtaPasswordCiphertext()));
  }

  private UserEntity requireTeacherUser(long teacherId) {
    UserEntity user = userRepository.findById(teacherId)
        .orElseThrow(() -> new IllegalArgumentException("teacher not found"));
    if (user.getRole() != UserRole.TEACHER && user.getRole() != UserRole.ADMIN) {
      throw new IllegalArgumentException("teacher role required");
    }
    return user;
  }

  private Map<String, Object> toSummaryMap(UserEntity user) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("ptaUsername", user.getPtaUsername());
    result.put("bound", hasStoredCredential(user));
    result.put("lastUpdated", user.getUpdatedAt());
    return result;
  }

  private boolean hasStoredCredential(UserEntity user) {
    return user.getPtaUsername() != null
        && !user.getPtaUsername().isBlank()
        && user.getPtaPasswordCiphertext() != null
        && !user.getPtaPasswordCiphertext().isBlank();
  }

  public record ResolvedPtaCredential(String username, String password) {}
}
