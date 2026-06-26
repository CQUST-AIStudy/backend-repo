package com.tap.backend.repository.practice;

import com.tap.backend.domain.practice.WrongQuestionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WrongQuestionRepository extends JpaRepository<WrongQuestionEntity, Long> {

  Optional<WrongQuestionEntity> findByStudentNoAndProblemIdAndSourceType(
      String studentNo, Long problemId, WrongQuestionEntity.SourceType sourceType);

  @Query("""
      SELECT w FROM WrongQuestionEntity w
      WHERE w.studentNo = :sid
        AND (:resolved IS NULL OR w.resolved = :resolved)
        AND (:sourceType IS NULL OR w.sourceType = :sourceType)
        AND (:errorCategory IS NULL OR w.errorCategory = :errorCategory)
        AND (:difficulty IS NULL OR w.difficulty = :difficulty)
        AND (:tag IS NULL OR LOWER(w.tagsCached) LIKE LOWER(CONCAT('%', :tag, '%')))
        AND (:q IS NULL OR LOWER(w.problemTitle) LIKE LOWER(CONCAT('%', :q, '%')))
      """)
  Page<WrongQuestionEntity> filter(@Param("sid") String studentNo,
                                    @Param("resolved") Boolean resolved,
                                    @Param("sourceType") WrongQuestionEntity.SourceType sourceType,
                                    @Param("errorCategory") String errorCategory,
                                    @Param("difficulty") String difficulty,
                                    @Param("tag") String tag,
                                    @Param("q") String q,
                                    Pageable pageable);

  long countByStudentNo(String studentNo);

  long countByStudentNoAndResolved(String studentNo, boolean resolved);

  @Query("SELECT w.errorCategory, COUNT(w) FROM WrongQuestionEntity w " +
         "WHERE w.studentNo = :sid AND w.errorCategory IS NOT NULL GROUP BY w.errorCategory")
  List<Object[]> countByErrorCategory(@Param("sid") String studentNo);

  @Query("SELECT w.difficulty, COUNT(w) FROM WrongQuestionEntity w " +
         "WHERE w.studentNo = :sid AND w.difficulty IS NOT NULL GROUP BY w.difficulty")
  List<Object[]> countByDifficulty(@Param("sid") String studentNo);
}
