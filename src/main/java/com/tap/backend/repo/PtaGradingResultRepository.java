package com.tap.backend.repo;

import com.tap.backend.domain.grading.PtaGradingResultEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PtaGradingResultRepository extends JpaRepository<PtaGradingResultEntity, Long> {

    List<PtaGradingResultEntity> findByOfferingIdOrderByStudentNoAsc(Long offeringId);

    Optional<PtaGradingResultEntity> findByOfferingIdAndStudentId(Long offeringId, Long studentId);

    Optional<PtaGradingResultEntity> findFirstByOfferingIdAndStudentNoAndPublishedTrue(Long offeringId, String studentNo);
}
