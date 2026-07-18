package com.tap.backend.repo;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
  Optional<UserEntity> findByUsername(String username);
  Optional<UserEntity> findByUsernum(String usernum);
  boolean existsByUsername(String username);
  List<UserEntity> findAllByOrderByCreatedAtDesc();
  long countByRole(UserRole role);
}
