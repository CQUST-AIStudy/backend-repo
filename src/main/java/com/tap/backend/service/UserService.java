package com.tap.backend.service;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Transactional(readOnly = true)
  public UserEntity requireById(long userId) {
    return userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
  }

  @Transactional(readOnly = true)
  public UserEntity requireByUsername(String username) {
    String u = username == null ? "" : username.trim();
    if (u.isBlank()) throw new IllegalArgumentException("username required");
    return userRepository.findByUsername(u).orElseThrow(() -> new IllegalArgumentException("user not found"));
  }
}
