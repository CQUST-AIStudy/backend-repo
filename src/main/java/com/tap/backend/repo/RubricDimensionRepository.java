package com.tap.backend.repo;

import com.tap.backend.domain.grading.RubricDimensionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RubricDimensionRepository extends JpaRepository<RubricDimensionEntity, Long> {
    List<RubricDimensionEntity> findAllByRubricIdOrderBySortOrderAsc(Long rubricId);
}
