package com.tap.backend.repo;

import com.tap.backend.domain.grading.GradingRubricEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface GradingRubricRepository extends JpaRepository<GradingRubricEntity, Long> {
    List<GradingRubricEntity> findAllByTeacherId(Long teacherId);
    List<GradingRubricEntity> findAllByTeacherIdAndSubject(Long teacherId, String subject);

    /**
     * Eagerly load dimensions so callers can build prompts outside an open Hibernate session
     * (e.g. inside a transaction afterCommit callback) without hitting LazyInitializationException.
     */
    @Query("select distinct r from GradingRubricEntity r left join fetch r.dimensions where r.id = :id")
    Optional<GradingRubricEntity> findByIdWithDimensions(@Param("id") Long id);
}
