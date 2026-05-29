package com.tap.backend.repo;

import com.tap.backend.domain.agent.AgentJobEntity;
import com.tap.backend.domain.agent.AgentJobStatus;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AgentJobRepository extends JpaRepository<AgentJobEntity, Long> {
  List<AgentJobEntity> findAllByUser_IdAndStatus(Long userId, AgentJobStatus status);
  List<AgentJobEntity> findAllByUploadFolder_Id(Long uploadFolderId);
  List<AgentJobEntity> findTop30ByUser_IdOrderByCreatedAtDesc(Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  AgentJobEntity findFirstByStatusOrderByCreatedAtAsc(AgentJobStatus status);
}
