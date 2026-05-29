package com.tap.backend.rag;

import com.tap.backend.domain.document.DocumentEntity;
import com.tap.backend.domain.rag.DocChunkEntity;
import com.tap.backend.repo.DocChunkRepository;
import com.tap.backend.repo.DocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final DashScopeEmbeddingClient embeddingClient;
    private final MilvusSearchService milvusSearch;
    private final DocChunkRepository docChunkRepo;
    private final DocumentRepository documentRepo;
    private final RagProperties props;

    public RagRetrievalService(DashScopeEmbeddingClient embeddingClient,
                               MilvusSearchService milvusSearch,
                               DocChunkRepository docChunkRepo,
                               DocumentRepository documentRepo,
                               RagProperties props) {
        this.embeddingClient = embeddingClient;
        this.milvusSearch = milvusSearch;
        this.docChunkRepo = docChunkRepo;
        this.documentRepo = documentRepo;
        this.props = props;
    }

    public record RetrievedContext(List<ParentEvidence> parents, double top1Score) {}

    public record ParentEvidence(
            long parentId,
            String content,
            String chapterPath,
            String pageRange,
            String docName,
            double score
    ) {}

    public RetrievedContext retrieve(long courseSpaceId, String query) {
        List<Float> queryVec = embeddingClient.embedQuery(query);
        List<MilvusSearchService.SearchHit> hits = milvusSearch.search(
                courseSpaceId, queryVec, props.retrieval().topK());

        if (hits.isEmpty()) {
            return new RetrievedContext(List.of(), 0.0);
        }

        double top1Score = hits.get(0).score();
        Map<Long, Double> parentScores = new LinkedHashMap<>();
        Map<Long, MilvusSearchService.SearchHit> parentHitMeta = new LinkedHashMap<>();
        for (MilvusSearchService.SearchHit hit : hits) {
            parentScores.merge(hit.parentId(), (double) hit.score(), Math::max);
            parentHitMeta.merge(hit.parentId(), hit,
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }

        List<Long> topParentIds = parentScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(props.retrieval().topParent())
                .map(Map.Entry::getKey)
                .toList();

        List<DocChunkEntity> parentChunks = docChunkRepo.findAllByIdIn(topParentIds);
        Map<Long, DocChunkEntity> chunkMap = parentChunks.stream()
                .collect(Collectors.toMap(DocChunkEntity::getId, Function.identity()));

        Set<Long> docIds = parentChunks.stream()
                .map(DocChunkEntity::getDocumentId)
                .collect(Collectors.toSet());
        Map<Long, String> docNames = documentRepo.findAllById(docIds).stream()
                .collect(Collectors.toMap(DocumentEntity::getId, DocumentEntity::getFilename));

        List<ParentEvidence> parents = new ArrayList<>();
        for (Long parentId : topParentIds) {
            DocChunkEntity chunk = chunkMap.get(parentId);
            if (chunk == null) {
                log.warn("Parent chunk {} not found in MySQL, skipping", parentId);
                continue;
            }
            MilvusSearchService.SearchHit meta = parentHitMeta.get(parentId);
            String docName = docNames.getOrDefault(chunk.getDocumentId(), "未知文档");

            parents.add(new ParentEvidence(
                    parentId,
                    chunk.getContent(),
                    meta != null ? meta.chapterPath() : chunk.getChapterPath(),
                    meta != null ? meta.pageRange() : chunk.getPageRange(),
                    docName,
                    parentScores.get(parentId)
            ));
        }

        log.debug("[RAG] retrieved {} parents for courseSpaceId={}, top1Score={}",
                parents.size(), courseSpaceId, top1Score);
        return new RetrievedContext(parents, top1Score);
    }
}
