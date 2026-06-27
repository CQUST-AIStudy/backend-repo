package com.tap.backend.repo;

import com.tap.backend.domain.classroom.TeachingClassEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeachingClassRepository extends JpaRepository<TeachingClassEntity, Long> {

    @EntityGraph(attributePaths = "teacher")
    List<TeachingClassEntity> findAllByTeacherId(Long teacherId);

    @EntityGraph(attributePaths = "teacher")
    List<TeachingClassEntity> findAllByTeacherIdAndStatus(Long teacherId, String status);

    @EntityGraph(attributePaths = "teacher")
    List<TeachingClassEntity> findAllByStatus(String status);

    Optional<TeachingClassEntity> findByClassCode(String classCode);
    boolean existsByClassCode(String classCode);
    List<TeachingClassEntity> findByNameContainingOrderByIdAsc(String name);

    @EntityGraph(attributePaths = "teacher")
    List<TeachingClassEntity> findAllByIdInAndStatus(Collection<Long> ids, String status);

    @EntityGraph(attributePaths = "teacher")
    @Override
    List<TeachingClassEntity> findAllById(Iterable<Long> ids);
}
