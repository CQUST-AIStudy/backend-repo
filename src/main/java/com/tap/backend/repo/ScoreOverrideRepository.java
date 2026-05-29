package com.tap.backend.repo;

import com.tap.backend.domain.grading.ScoreOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScoreOverrideRepository extends JpaRepository<ScoreOverrideEntity, Long> {
    List<ScoreOverrideEntity> findAllByScoreItemId(Long scoreItemId);
}
