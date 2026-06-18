package com.tap.backend.repository.grading;

import com.tap.backend.domain.grading.GradingAgentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GradingAgentConfigRepository extends JpaRepository<GradingAgentConfigEntity, Long> {
    Optional<GradingAgentConfigEntity> findByCode(String code);
}
