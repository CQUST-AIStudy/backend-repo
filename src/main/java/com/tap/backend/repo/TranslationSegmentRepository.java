package com.tap.backend.repo;

import com.tap.backend.domain.translation.TranslationSegmentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface TranslationSegmentRepository extends JpaRepository<TranslationSegmentEntity, Long> {
  List<TranslationSegmentEntity> findAllByDocument_IdAndTargetLangOrderBySegmentIndexAsc(Long documentId, String targetLang);
  long countByDocument_IdAndTargetLang(Long documentId, String targetLang);
  @Modifying
  @Transactional
  void deleteAllByDocument_IdAndTargetLang(Long documentId, String targetLang);
}
