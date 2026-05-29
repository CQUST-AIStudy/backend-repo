package com.tap.backend.repo;

import com.tap.backend.domain.rag.CourseSpaceDocumentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseSpaceDocumentRepository extends JpaRepository<CourseSpaceDocumentEntity, Long> {
    List<CourseSpaceDocumentEntity> findAllByCourseSpaceId(Long courseSpaceId);

    Optional<CourseSpaceDocumentEntity> findByCourseSpaceIdAndDocumentId(Long courseSpaceId, Long documentId);

    long countByCourseSpaceId(Long courseSpaceId);
}
