package com.tap.backend.repo;

import com.tap.backend.domain.grading.ScoreItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ScoreItemRepository extends JpaRepository<ScoreItemEntity, Long> {
    List<ScoreItemEntity> findAllBySubmissionId(Long submissionId);
    Optional<ScoreItemEntity> findBySubmissionIdAndDimensionId(Long submissionId, Long dimensionId);
}
