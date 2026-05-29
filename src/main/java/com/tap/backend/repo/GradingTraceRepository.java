package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GradingTraceRepository extends JpaRepository<GradingTraceEntity, Long> {
    List<GradingTraceEntity> findAllBySubmissionId(Long submissionId);
    List<GradingTraceEntity> findAllBySubmissionIdOrderByCreatedAtAsc(Long submissionId);
    GradingTraceEntity findTopBySubmissionIdOrderByCreatedAtDesc(Long submissionId);
}
