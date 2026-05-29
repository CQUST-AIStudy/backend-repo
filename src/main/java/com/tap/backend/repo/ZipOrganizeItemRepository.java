package com.tap.backend.repo;

import com.tap.backend.domain.ziporganize.ZipOrganizeItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZipOrganizeItemRepository extends JpaRepository<ZipOrganizeItemEntity, Long> {
  List<ZipOrganizeItemEntity> findAllByJob_IdOrderByOriginalPathAsc(Long jobId);
  void deleteAllByJob_Id(Long jobId);
}
