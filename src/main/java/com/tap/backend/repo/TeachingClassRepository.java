package com.tap.backend.repo;

import com.tap.backend.domain.classroom.TeachingClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TeachingClassRepository extends JpaRepository<TeachingClassEntity, Long> {
    List<TeachingClassEntity> findAllByTeacherId(Long teacherId);
    Optional<TeachingClassEntity> findByClassCode(String classCode);
    boolean existsByClassCode(String classCode);
    List<TeachingClassEntity> findByNameContainingOrderByIdAsc(String name);
}
