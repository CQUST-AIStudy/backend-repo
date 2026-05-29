package com.tap.backend.repo;

import com.tap.backend.domain.rag.CourseSpaceClassEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseSpaceClassRepository extends JpaRepository<CourseSpaceClassEntity, Long> {
    List<CourseSpaceClassEntity> findAllByCourseSpaceId(Long courseSpaceId);
    List<CourseSpaceClassEntity> findAllByClassIdIn(Collection<Long> classIds);
    boolean existsByCourseSpaceIdAndClassIdIn(Long courseSpaceId, Collection<Long> classIds);
    void deleteAllByCourseSpaceId(Long courseSpaceId);
}
