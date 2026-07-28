package com.tap.backend.repo;

import com.tap.backend.domain.teaching.TeachingAdviceReportEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeachingAdviceReportRepository extends JpaRepository<TeachingAdviceReportEntity, Long> {
    List<TeachingAdviceReportEntity> findTop20ByTeacherIdOrderByCreatedAtDesc(Long teacherId);
    List<TeachingAdviceReportEntity> findTop10ByTeacherIdAndSourceHashOrderByCreatedAtDesc(Long teacherId, String sourceHash);
    Optional<TeachingAdviceReportEntity> findByIdAndTeacherId(Long id, Long teacherId);
}
