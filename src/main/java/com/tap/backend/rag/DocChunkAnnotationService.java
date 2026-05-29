package com.tap.backend.rag;

import com.tap.backend.domain.rag.DocChunkAnnotationEntity;
import com.tap.backend.domain.rag.DocChunkEntity;
import com.tap.backend.repo.DocChunkAnnotationRepository;
import com.tap.backend.repo.DocChunkRepository;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DocChunkAnnotationService {

    private final DocChunkAnnotationRepository annotationRepo;
    private final DocChunkRepository docChunkRepo;

    public DocChunkAnnotationService(DocChunkAnnotationRepository annotationRepo,
                                      DocChunkRepository docChunkRepo) {
        this.annotationRepo = annotationRepo;
        this.docChunkRepo = docChunkRepo;
    }

    public DocChunkAnnotationEntity create(Long chunkId, String annotationType,
                                            String note, Long teacherId) {
        DocChunkAnnotationEntity entity = new DocChunkAnnotationEntity();
        entity.setChunkId(chunkId);
        entity.setAnnotationType(annotationType);
        entity.setNote(note);
        entity.setTeacherId(teacherId);
        return annotationRepo.save(entity);
    }

    public List<DocChunkAnnotationEntity> listByChunk(Long chunkId) {
        return annotationRepo.findAllByChunkId(chunkId);
    }

    public List<DocChunkAnnotationEntity> listByCourseSpace(Long courseSpaceId) {
        List<DocChunkEntity> chunks = docChunkRepo.findAllByCourseSpaceId(courseSpaceId);
        if (chunks.isEmpty()) return Collections.emptyList();

        List<Long> chunkIds = chunks.stream()
                .map(DocChunkEntity::getId)
                .collect(Collectors.toList());
        return annotationRepo.findAllByChunkIdIn(chunkIds);
    }

    public void delete(Long annotationId, Long teacherId) {
        DocChunkAnnotationEntity entity = annotationRepo.findById(annotationId)
                .orElseThrow(() -> new IllegalArgumentException("Annotation not found: " + annotationId));
        if (!entity.getTeacherId().equals(teacherId)) {
            throw new SecurityException("Not authorized to delete this annotation");
        }
        annotationRepo.deleteById(annotationId);
    }
}
