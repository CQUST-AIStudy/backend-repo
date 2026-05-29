package com.tap.backend.repo;

import com.tap.backend.domain.summary.StructuredSummaryEntity;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface StructuredSummaryRepository extends JpaRepository<StructuredSummaryEntity, Long> {
  Optional<StructuredSummaryEntity> findByScopeTypeAndScopeKeyAndProviderAndModel(
      String scopeType, String scopeKey, String provider, String model);

  @Modifying
  @Transactional
  void deleteAllByScopeTypeAndScopeKey(String scopeType, String scopeKey);

  @Modifying
  @Transactional
  void deleteAllByScopeTypeAndScopeKeyIn(String scopeType, Collection<String> scopeKeys);
}
