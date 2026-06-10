package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.GradingTaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface GradingTaskRepository extends JpaRepository<GradingTaskEntity, Long> {
    Page<GradingTaskEntity> findAllByTeacherId(Long teacherId, Pageable pageable);
    Page<GradingTaskEntity> findAllByTeacherIdAndStatus(Long teacherId, GradingTaskStatus status, Pageable pageable);
    boolean existsByRubricIdAndStatus(Long rubricId, GradingTaskStatus status);

    @Modifying
    @Query("UPDATE GradingTaskEntity t SET t.completedCount = t.completedCount + 1 WHERE t.id = :taskId")
    void incrementCompletedCount(@Param("taskId") Long taskId);

    @Modifying
    @Query("UPDATE GradingTaskEntity t SET t.failedCount = t.failedCount + 1 WHERE t.id = :taskId")
    void incrementFailedCount(@Param("taskId") Long taskId);

    /**
     * Count tasks created on or after a given timestamp for a specific teacher.
     * Used to generate the daily sequence number in display codes (MMDD-XX).
     */
    long countByTeacherIdAndCreatedAtAfter(Long teacherId, Instant after);

    List<GradingTaskEntity> findAllByBatchIdOrderByCreatedAtAsc(Long batchId);
    List<GradingTaskEntity> findAllByBatchIdIn(java.util.Collection<Long> batchIds);
}
