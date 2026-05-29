package com.tap.backend.repo;

import com.tap.backend.domain.rag.ChapterSummaryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChapterSummaryRepository extends JpaRepository<ChapterSummaryEntity, Long> {

    List<ChapterSummaryEntity> findAllByDocId(Long docId);

    List<ChapterSummaryEntity> findAllByCourseSpaceId(Long courseSpaceId);
}
