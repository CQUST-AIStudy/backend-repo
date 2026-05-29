package com.tap.backend.repo;

import com.tap.backend.domain.upload.UploadFolderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadFolderRepository extends JpaRepository<UploadFolderEntity, Long> {
  List<UploadFolderEntity> findAllByUser_Id(Long userId);
  Optional<UploadFolderEntity> findByIdAndUser_Id(Long id, Long userId);
}
