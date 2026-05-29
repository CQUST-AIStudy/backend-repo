package com.tap.backend.repo;

import com.tap.backend.domain.grading.EvidenceBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EvidenceBlockRepository extends JpaRepository<EvidenceBlockEntity, Long> {
    List<EvidenceBlockEntity> findAllBySubmissionId(Long submissionId);
    Optional<EvidenceBlockEntity> findByEvidenceId(String evidenceId);
}
