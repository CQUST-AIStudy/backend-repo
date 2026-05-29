package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface GradingSubmissionRepository extends JpaRepository<GradingSubmissionEntity, Long> {
    List<GradingSubmissionEntity> findAllByTaskId(Long taskId);
    List<GradingSubmissionEntity> findAllByTaskIdAndStatus(Long taskId, SubmissionStatus status);
    List<GradingSubmissionEntity> findAllByTaskIdAndIdIn(Long taskId, Collection<Long> submissionIds);
    List<GradingSubmissionEntity> findAllByStatusAndUpdatedAtBefore(SubmissionStatus status, Instant updatedAtBefore);
    int countByTaskIdAndStatus(Long taskId, SubmissionStatus status);
}
