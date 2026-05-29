package com.tap.backend.repo;

import com.tap.backend.domain.rag.DocChunkAnnotationEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocChunkAnnotationRepository extends JpaRepository<DocChunkAnnotationEntity, Long> {

    List<DocChunkAnnotationEntity> findAllByChunkId(Long chunkId);

    List<DocChunkAnnotationEntity> findAllByChunkIdIn(Collection<Long> chunkIds);
}
