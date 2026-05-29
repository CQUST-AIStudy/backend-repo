package com.tap.backend.repo;

import com.tap.backend.domain.grading.ReportFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReportFileRepository extends JpaRepository<ReportFileEntity, Long> {
    List<ReportFileEntity> findAllByTaskId(Long taskId);
    List<ReportFileEntity> findAllByTaskIdAndFileType(Long taskId, String fileType);
    List<ReportFileEntity> findAllBySubmissionIdOrderByCreatedAtDesc(Long submissionId);
    List<ReportFileEntity> findAllBySubmissionIdAndFileTypeOrderByCreatedAtDesc(Long submissionId, String fileType);
    Optional<ReportFileEntity> findBySubmissionIdAndFileType(Long submissionId, String fileType);
}
