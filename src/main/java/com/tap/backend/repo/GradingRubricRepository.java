package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingRubricEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GradingRubricRepository extends JpaRepository<GradingRubricEntity, Long> {
    List<GradingRubricEntity> findAllByTeacherId(Long teacherId);
    List<GradingRubricEntity> findAllByTeacherIdAndSubject(Long teacherId, String subject);
}
