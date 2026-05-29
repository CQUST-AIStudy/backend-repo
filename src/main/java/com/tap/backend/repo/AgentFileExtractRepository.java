package com.tap.backend.repo;

import com.tap.backend.domain.agent.AgentFileExtractEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentFileExtractRepository extends JpaRepository<AgentFileExtractEntity, Long> {
    Optional<AgentFileExtractEntity> findByJobFileId(Long jobFileId);
}
