package com.tap.backend.repo;

import com.tap.backend.domain.rag.DocChunkEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocChunkRepository extends JpaRepository<DocChunkEntity, Long> {
    List<DocChunkEntity> findAllByParentId(Long parentId);

    List<DocChunkEntity> findAllByCourseSpaceIdAndChunkType(Long courseSpaceId, String chunkType);

    List<DocChunkEntity> findAllByCourseSpaceIdAndDocumentIdAndChunkType(
            Long courseSpaceId, Long documentId, String chunkType);

    List<DocChunkEntity> findAllByIdIn(Collection<Long> ids);

    List<DocChunkEntity> findAllByChunkType(String chunkType);

    List<DocChunkEntity> findAllByCourseSpaceId(Long courseSpaceId);
}
