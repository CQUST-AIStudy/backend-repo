package com.tap.backend.repo;

import com.tap.backend.domain.agent.AgentJobFileEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AgentJobFileRepository extends JpaRepository<AgentJobFileEntity, Long> {
    List<AgentJobFileEntity> findAllByJobId(Long jobId);
    @Transactional
    void deleteAllByJobId(Long jobId);
}
