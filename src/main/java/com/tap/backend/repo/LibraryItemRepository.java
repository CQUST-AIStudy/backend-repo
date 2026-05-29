package com.tap.backend.repo;

import com.tap.backend.domain.library.LibraryItemEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryItemRepository extends JpaRepository<LibraryItemEntity, Long> {
  List<LibraryItemEntity> findAllByUser_Id(Long userId);
  Optional<LibraryItemEntity> findByUser_IdAndPaper_Id(Long userId, Long paperId);
}
