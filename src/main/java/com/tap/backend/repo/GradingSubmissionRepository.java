package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface GradingSubmissionRepository extends JpaRepository<GradingSubmissionEntity, Long> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"task"})
    List<GradingSubmissionEntity> findAllByTaskId(Long taskId);
    List<GradingSubmissionEntity> findAllByTaskIdAndStatus(Long taskId, SubmissionStatus status);
    List<GradingSubmissionEntity> findAllByTaskIdAndIdIn(Long taskId, Collection<Long> submissionIds);
    List<GradingSubmissionEntity> findAllByStatusAndUpdatedAtBefore(SubmissionStatus status, Instant updatedAtBefore);
    int countByTaskIdAndStatus(Long taskId, SubmissionStatus status);

    @Query("SELECT s FROM GradingSubmissionEntity s "
            + "WHERE s.task.experimentId = :experimentId "
            + "AND s.studentNo = :studentNo "
            + "AND s.publishedAt IS NOT NULL "
            + "ORDER BY s.publishedAt DESC")
    List<GradingSubmissionEntity> findPublishedByExperimentAndStudentNo(
            @Param("experimentId") Long experimentId,
            @Param("studentNo") String studentNo);

    /**
     * Resolves a student's published submissions when the client passes the id that the
     * unified student experiment list exposes (an {@code assignment_offering.id}). Matches
     * either the task's {@code assignmentOfferingId} (unified id space) or its legacy
     * {@code experimentId}, keeping backward compatibility with older tasks.
     */
    @Query("SELECT s FROM GradingSubmissionEntity s "
            + "WHERE (s.task.assignmentOfferingId = :listId OR s.task.experimentId = :listId) "
            + "AND s.studentNo = :studentNo "
            + "AND s.publishedAt IS NOT NULL "
            + "ORDER BY s.publishedAt DESC")
    List<GradingSubmissionEntity> findPublishedByOfferingOrExperimentAndStudentNo(
            @Param("listId") Long listId,
            @Param("studentNo") String studentNo);
}
