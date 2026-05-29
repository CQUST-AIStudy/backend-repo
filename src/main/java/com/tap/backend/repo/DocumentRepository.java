package com.tap.backend.repo;

import com.tap.backend.domain.document.DocumentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {
  Optional<DocumentEntity> findFirstBySha256OrderByIdAsc(String sha256);
  List<DocumentEntity> findAllByUploadFolder_Id(Long uploadFolderId);
  Optional<DocumentEntity> findByIdAndUser_Id(Long id, Long userId);
  List<DocumentEntity> findAllByUser_Id(Long userId);
  List<DocumentEntity> findAllByUser_IdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
