package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingBatchEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GradingBatchRepository extends JpaRepository<GradingBatchEntity, Long> {
    List<GradingBatchEntity> findAllByTeacherIdOrderByCreatedAtDesc(Long teacherId);

    /**
     * Count batches created on or after a given timestamp for a specific teacher.
     * Used to generate the daily sequence number in batch display codes (MMDD-XX).
     */
    long countByTeacherIdAndCreatedAtAfter(Long teacherId, Instant after);
}
