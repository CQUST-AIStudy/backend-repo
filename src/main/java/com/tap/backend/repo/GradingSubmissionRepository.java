package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GradingSubmissionRepository extends JpaRepository<GradingSubmissionEntity, Long> {
    List<GradingSubmissionEntity> findAllByTaskId(Long taskId);
    List<GradingSubmissionEntity> findAllByTaskIdAndStatus(Long taskId, SubmissionStatus status);
    List<GradingSubmissionEntity> findAllByTaskIdAndIdIn(Long taskId, Collection<Long> submissionIds);
    List<GradingSubmissionEntity> findAllByStatusAndUpdatedAtBefore(SubmissionStatus status, Instant updatedAtBefore);
    int countByTaskIdAndStatus(Long taskId, SubmissionStatus status);

    @Query("""
            SELECT s FROM GradingSubmissionEntity s
            WHERE s.studentNo = :studentNo
              AND s.publishedAt IS NOT NULL
              AND (s.task.assignmentOfferingId = :experimentId OR s.task.experimentId = :experimentId)
            ORDER BY s.publishedAt DESC, s.id DESC
            """)
    List<GradingSubmissionEntity> findPublishedForStudentExperiment(
            @Param("studentNo") String studentNo,
            @Param("experimentId") Long experimentId);

    @Query("""
            SELECT s FROM GradingSubmissionEntity s
            WHERE s.studentNo = :studentNo
              AND s.publishedAt IS NOT NULL
            ORDER BY s.publishedAt DESC, s.id DESC
            """)
    List<GradingSubmissionEntity> findLatestPublishedForStudent(@Param("studentNo") String studentNo);

    Optional<GradingSubmissionEntity> findByIdAndStudentNoAndPublishedAtIsNotNull(
            Long id, String studentNo);
}
