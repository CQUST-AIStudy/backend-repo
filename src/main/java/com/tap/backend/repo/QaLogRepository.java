package com.tap.backend.repo;

import com.tap.backend.domain.rag.QaLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QaLogRepository extends JpaRepository<QaLogEntity, Long> {

    List<QaLogEntity> findAllByCourseSpaceId(Long courseSpaceId);

    @Query("SELECT q.query, COUNT(q) as cnt FROM QaLogEntity q WHERE q.courseSpaceId = :csId GROUP BY q.query ORDER BY cnt DESC")
    List<Object[]> findHotQuestions(@Param("csId") Long courseSpaceId);

    long countByCourseSpaceId(Long courseSpaceId);

    long countByCourseSpaceIdAndCoverageScoreGreaterThan(Long courseSpaceId, Double threshold);

    long countByCourseSpaceIdAndUsedWeb(Long courseSpaceId, Boolean usedWeb);

    long countByCourseSpaceIdAndFeedback(Long courseSpaceId, Integer feedback);
}
