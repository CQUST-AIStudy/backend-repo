package com.tap.backend.repo;

import com.tap.backend.domain.agent.AgentOrganizePlanEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AgentOrganizePlanRepository extends JpaRepository<AgentOrganizePlanEntity, Long> {
    List<AgentOrganizePlanEntity> findAllByJobId(Long jobId);
    @Transactional
    void deleteAllByJobId(Long jobId);
}
