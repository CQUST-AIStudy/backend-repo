package com.tap.backend.repo;

import com.tap.backend.domain.agent.AgentResultEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentResultRepository extends JpaRepository<AgentResultEntity, Long> {
  Optional<AgentResultEntity> findByJob_Id(Long jobId);
}
