package com.tap.backend.repo;

import com.tap.backend.domain.paper.PaperEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperRepository extends JpaRepository<PaperEntity, Long> {
  Optional<PaperEntity> findByArxivId(String arxivId);
}
