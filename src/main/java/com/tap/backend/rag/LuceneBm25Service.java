package com.tap.backend.rag;

import com.tap.backend.domain.rag.DocChunkEntity;
import com.tap.backend.repo.DocChunkRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LuceneBm25Service {

    private static final Logger log = LoggerFactory.getLogger(LuceneBm25Service.class);

    private final DocChunkRepository docChunkRepo;
    private final RagProperties ragProps;
    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    public LuceneBm25Service(DocChunkRepository docChunkRepo, RagProperties ragProps) {
        this.docChunkRepo = docChunkRepo;
        this.ragProps = ragProps;
    }

    public record Bm25Hit(long chunkId, long parentId, long courseSpaceId,
                          long docId, String chapterPath, String pageRange, float score) {}

    @PostConstruct
    void init() {
        try {
            Path indexPath = resolveIndexPath();
            Files.createDirectories(indexPath);
            directory = FSDirectory.open(indexPath);
            analyzer = new SmartChineseAnalyzer();

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(OpenMode.CREATE);
            writer = new IndexWriter(directory, config);

            List<DocChunkEntity> children = docChunkRepo.findAllByChunkType("child");
            for (DocChunkEntity chunk : children) {
                addDocument(chunk);
            }
            writer.commit();
            searcherManager = new SearcherManager(writer, true, true, null);
            log.info("[BM25] Index built with {} child chunks at {}", children.size(), indexPath);
        } catch (LockObtainFailedException e) {
            log.warn("[BM25] Index lock is held by another process, opening read-only searcher instead");
            initReadOnlySearcher();
        } catch (Exception e) {
            log.error("[BM25] Failed to build index, degrading to vector-only mode", e);
        }
    }

    public void addChunks(List<DocChunkEntity> newChunks) {
        if (writer == null || newChunks == null || newChunks.isEmpty()) {
            return;
        }
        try {
            for (DocChunkEntity chunk : newChunks) {
                addDocument(chunk);
            }
            refreshIndex();
            log.debug("[BM25] Added {} chunks to index", newChunks.size());
        } catch (IOException e) {
            log.error("[BM25] Failed to add chunks to index", e);
        }
    }

    public void deleteByDocument(long docId) {
        deleteByTerm(new Term("doc_id", String.valueOf(docId)));
    }

    public void deleteByCourseSpace(long courseSpaceId) {
        deleteByTerm(new Term("course_space_id", String.valueOf(courseSpaceId)));
    }

    public synchronized void rebuildAll() {
        if (writer == null) {
            return;
        }
        try {
            writer.deleteAll();
            for (DocChunkEntity chunk : docChunkRepo.findAllByChunkType("child")) {
                addDocument(chunk);
            }
            refreshIndex();
            log.info("[BM25] Rebuilt full index");
        } catch (IOException e) {
            log.error("[BM25] Failed to rebuild full index", e);
        }
    }

    public synchronized void rebuildCourseSpace(long courseSpaceId) {
        if (writer == null) {
            return;
        }
        try {
            writer.deleteDocuments(new Term("course_space_id", String.valueOf(courseSpaceId)));
            List<DocChunkEntity> chunks = docChunkRepo.findAllByCourseSpaceIdAndChunkType(courseSpaceId, "child");
            for (DocChunkEntity chunk : chunks) {
                addDocument(chunk);
            }
            refreshIndex();
            log.info("[BM25] Rebuilt index for courseSpaceId={} with {} chunks", courseSpaceId, chunks.size());
        } catch (IOException e) {
            log.error("[BM25] Failed to rebuild index for courseSpaceId={}", courseSpaceId, e);
        }
    }

    public List<Bm25Hit> search(long courseSpaceId, String query, int topK) {
        if (searcherManager == null || query == null || query.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            BooleanQuery.Builder boolBuilder = new BooleanQuery.Builder();
            boolBuilder.add(LongPoint.newExactQuery("course_space_id_point", courseSpaceId), BooleanClause.Occur.FILTER);

            org.apache.lucene.queryparser.classic.QueryParser parser =
                    new org.apache.lucene.queryparser.classic.QueryParser("content", analyzer);
            Query textQuery = parser.parse(org.apache.lucene.queryparser.classic.QueryParser.escape(query));
            boolBuilder.add(textQuery, BooleanClause.Occur.MUST);

            TopDocs topDocs = searcher.search(boolBuilder.build(), topK);
            List<Bm25Hit> hits = new ArrayList<>();
            float maxScore = topDocs.scoreDocs.length > 0 ? topDocs.scoreDocs[0].score : 1.0f;
            if (maxScore <= 0) {
                maxScore = 1.0f;
            }

            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                hits.add(new Bm25Hit(
                        Long.parseLong(doc.get("chunk_id")),
                        Long.parseLong(doc.get("parent_id")),
                        Long.parseLong(doc.get("course_space_id")),
                        Long.parseLong(doc.get("doc_id")),
                        doc.get("chapter_path"),
                        doc.get("page_range"),
                        sd.score / maxScore
                ));
            }
            return hits;
        } catch (Exception e) {
            log.error("[BM25] Search failed", e);
            return Collections.emptyList();
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public boolean isAvailable() {
        return searcherManager != null;
    }

    private void initReadOnlySearcher() {
        if (directory == null) {
            return;
        }
        try {
            if (!DirectoryReader.indexExists(directory)) {
                log.warn("[BM25] No existing Lucene index found for read-only fallback");
                return;
            }
            DirectoryReader reader = DirectoryReader.open(directory);
            searcherManager = new SearcherManager(reader, null);
            writer = null;
            log.info("[BM25] Read-only searcher opened against existing index");
        } catch (Exception ex) {
            log.error("[BM25] Failed to open read-only searcher", ex);
        }
    }

    private void addDocument(DocChunkEntity chunk) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("chunk_id", String.valueOf(chunk.getId()), Field.Store.YES));
        doc.add(new StringField("parent_id", String.valueOf(chunk.getParentId() != null ? chunk.getParentId() : 0), Field.Store.YES));

        Long courseSpaceId = chunk.getCourseSpaceId();
        if (courseSpaceId == null && chunk.getCourseSpace() != null) {
            courseSpaceId = chunk.getCourseSpace().getId();
        }
        Long documentId = chunk.getDocumentId();
        if (documentId == null && chunk.getDocument() != null) {
            documentId = chunk.getDocument().getId();
        }

        doc.add(new StringField("course_space_id", String.valueOf(courseSpaceId), Field.Store.YES));
        doc.add(new LongPoint("course_space_id_point", courseSpaceId != null ? courseSpaceId : 0));
        doc.add(new StringField("doc_id", String.valueOf(documentId), Field.Store.YES));
        doc.add(new StringField("chapter_path", chunk.getChapterPath() != null ? chunk.getChapterPath() : "", Field.Store.YES));
        doc.add(new StringField("page_range", chunk.getPageRange() != null ? chunk.getPageRange() : "", Field.Store.YES));
        doc.add(new TextField("content", chunk.getContent(), Field.Store.NO));
        writer.updateDocument(new Term("chunk_id", String.valueOf(chunk.getId())), doc);
    }

    private void deleteByTerm(Term term) {
        if (writer == null) {
            return;
        }
        try {
            writer.deleteDocuments(term);
            refreshIndex();
            log.debug("[BM25] Deleted index entries for {}", term);
        } catch (IOException e) {
            log.warn("[BM25] Failed to delete index entries for {}", term, e);
        }
    }

    private void refreshIndex() throws IOException {
        writer.commit();
        if (searcherManager != null) {
            searcherManager.maybeRefresh();
        }
    }

    private Path resolveIndexPath() {
        String configured = ragProps.lucene() == null ? null : ragProps.lucene().indexPath();
        if (configured == null || configured.isBlank()) {
            configured = "./data/lucene-index";
        }
        return Path.of(configured).normalize();
    }

    @PreDestroy
    void close() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
            if (writer != null) {
                writer.close();
            }
            if (directory != null) {
                directory.close();
            }
        } catch (IOException e) {
            log.error("[BM25] Error closing resources", e);
        }
    }
}
