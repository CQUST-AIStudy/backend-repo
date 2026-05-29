package com.tap.backend.repo;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
  Optional<UserEntity> findByUsername(String username);
  long countByRole(UserRole role);
}
