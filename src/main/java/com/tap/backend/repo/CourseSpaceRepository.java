package com.tap.backend.repo;

import com.tap.backend.domain.rag.CourseSpaceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseSpaceRepository extends JpaRepository<CourseSpaceEntity, Long> {
    List<CourseSpaceEntity> findAllByTeacherId(Long teacherId);
    List<CourseSpaceEntity> findAllByDocVisibilityIgnoreCase(String docVisibility);
}
