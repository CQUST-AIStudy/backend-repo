package com.tap.backend.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.exception.ParamException;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MilvusSearchService {

  private static final Logger log = LoggerFactory.getLogger(MilvusSearchService.class);
  private static final List<String> OUTPUT_FIELDS =
      List.of("parent_id", "course_space_id", "doc_id", "chapter_path", "page_range");
  private static final int CHAPTER_MAX_LEN = 1024;
  private static final int PAGE_RANGE_MAX_LEN = 128;
  private static final long MILVUS_WAIT_INTERVAL_SECONDS = 1L;
  private static final long MILVUS_WAIT_TIMEOUT_SECONDS = 30L;

  private final RagProperties props;
  private volatile MilvusServiceClient client;
  private volatile boolean collectionEnsured;

  public MilvusSearchService(RagProperties props) {
    this.props = props;
  }

  public record SearchHit(
      long chunkId,
      long parentId,
      long courseSpaceId,
      long docId,
      String chapterPath,
      String pageRange,
      float score) {}

  public record ChunkVector(
      long chunkId,
      long parentId,
      long courseSpaceId,
      long docId,
      String chapterPath,
      String pageRange,
      List<Float> vector) {}

  public List<SearchHit> search(long courseSpaceId, List<Float> queryVector, int topK) {
    if (queryVector == null || queryVector.isEmpty() || topK <= 0) {
      return Collections.emptyList();
    }
    MilvusServiceClient c;
    try {
      c = getClient();
      ensureCollection(c);
    } catch (Exception e) {
      log.warn("[Milvus] unavailable, degrading to BM25-only: {}", e.getMessage());
      return Collections.emptyList();
    }

    SearchParam searchParam =
        SearchParam.newBuilder()
            .withCollectionName(collectionName())
            .withMetricType(MetricType.COSINE)
            .withTopK(topK)
            .withVectors(Collections.singletonList(queryVector))
            .withVectorFieldName("vector")
            .withOutFields(OUTPUT_FIELDS)
            .withExpr("course_space_id == " + courseSpaceId)
            .withParams("{\"ef\": 128}")
            .build();

    R<SearchResults> resp = c.search(searchParam);
    if (!isSuccess(resp)) {
      throw new RuntimeException("Milvus search failed: " + resp.getMessage());
    }

    SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
    List<SearchHit> hits = new ArrayList<>();
    List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
    for (int i = 0; i < scores.size(); i++) {
      SearchResultsWrapper.IDScore idScore = scores.get(i);
      hits.add(
          new SearchHit(
              idScore.getLongID(),
              toLong(wrapper.getFieldData("parent_id", 0).get(i)),
              toLong(wrapper.getFieldData("course_space_id", 0).get(i)),
              toLong(wrapper.getFieldData("doc_id", 0).get(i)),
              toStringValue(wrapper.getFieldData("chapter_path", 0).get(i)),
              toStringValue(wrapper.getFieldData("page_range", 0).get(i)),
              idScore.getScore()));
    }
    return hits;
  }

  public void upsertChunks(List<ChunkVector> rows) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    MilvusServiceClient c = getClient();
    ensureCollection(c);

    List<Long> chunkIds = new ArrayList<>(rows.size());
    List<Long> parentIds = new ArrayList<>(rows.size());
    List<Long> courseSpaceIds = new ArrayList<>(rows.size());
    List<Long> docIds = new ArrayList<>(rows.size());
    List<String> chapterPaths = new ArrayList<>(rows.size());
    List<String> pageRanges = new ArrayList<>(rows.size());
    List<List<Float>> vectors = new ArrayList<>(rows.size());

    for (ChunkVector row : rows) {
      chunkIds.add(row.chunkId());
      parentIds.add(row.parentId());
      courseSpaceIds.add(row.courseSpaceId());
      docIds.add(row.docId());
      chapterPaths.add(truncate(row.chapterPath(), CHAPTER_MAX_LEN));
      pageRanges.add(truncate(row.pageRange(), PAGE_RANGE_MAX_LEN));
      vectors.add(row.vector());
    }

    List<InsertParam.Field> fields =
        List.of(
            InsertParam.Field.builder().name("chunk_id").values(chunkIds).build(),
            InsertParam.Field.builder().name("parent_id").values(parentIds).build(),
            InsertParam.Field.builder()
                .name("course_space_id")
                .values(courseSpaceIds)
                .build(),
            InsertParam.Field.builder().name("doc_id").values(docIds).build(),
            InsertParam.Field.builder()
                .name("chapter_path")
                .values(chapterPaths)
                .build(),
            InsertParam.Field.builder().name("page_range").values(pageRanges).build(),
            InsertParam.Field.builder().name("vector").values(vectors).build());

    UpsertParam upsertParam =
        UpsertParam.newBuilder().withCollectionName(collectionName()).withFields(fields).build();
    R<MutationResult> resp = c.upsert(upsertParam);
    ensureSuccess(resp, "upsert");
    flushAndLoad(c);
    log.info("[Milvus] upserted {} child chunks into {}", rows.size(), collectionName());
  }

  public void deleteByDocument(long docId) {
    deleteByExpr("doc_id == " + docId);
  }

  public void deleteByCourseSpace(long courseSpaceId) {
    deleteByExpr("course_space_id == " + courseSpaceId);
  }

  public void ensureCollection() {
    ensureCollection(getClient());
  }

  private void deleteByExpr(String expr) {
    MilvusServiceClient c = getClient();
    ensureCollection(c);
    DeleteParam deleteParam =
        DeleteParam.newBuilder().withCollectionName(collectionName()).withExpr(expr).build();
    R<MutationResult> resp = c.delete(deleteParam);
    ensureSuccess(resp, "delete");
    flushAndLoad(c);
    log.info("[Milvus] deleted rows by expr: {}", expr);
  }

  private void ensureCollection(MilvusServiceClient c) {
    if (collectionEnsured) {
      return;
    }
    synchronized (this) {
      if (collectionEnsured) {
        return;
      }

      R<Boolean> hasResp =
          c.hasCollection(
              HasCollectionParam.newBuilder().withCollectionName(collectionName()).build());
      ensureSuccess(hasResp, "hasCollection");

      if (!Boolean.TRUE.equals(hasResp.getData())) {
        createCollection(c);
        createVectorIndex(c);
      }

      loadCollection(c);
      collectionEnsured = true;
    }
  }

  private void createCollection(MilvusServiceClient c) {
    int dim = embeddingDimension();
    List<FieldType> fields = new ArrayList<>();
    fields.add(
        FieldType.newBuilder()
            .withName("chunk_id")
            .withDataType(DataType.Int64)
            .withPrimaryKey(true)
            .withAutoID(false)
            .build());
    fields.add(
        FieldType.newBuilder().withName("parent_id").withDataType(DataType.Int64).build());
    fields.add(
        FieldType.newBuilder()
            .withName("course_space_id")
            .withDataType(DataType.Int64)
            .build());
    fields.add(FieldType.newBuilder().withName("doc_id").withDataType(DataType.Int64).build());
    fields.add(
        FieldType.newBuilder()
            .withName("chapter_path")
            .withDataType(DataType.VarChar)
            .withMaxLength(CHAPTER_MAX_LEN)
            .build());
    fields.add(
        FieldType.newBuilder()
            .withName("page_range")
            .withDataType(DataType.VarChar)
            .withMaxLength(PAGE_RANGE_MAX_LEN)
            .build());
    fields.add(
        FieldType.newBuilder()
            .withName("vector")
            .withDataType(DataType.FloatVector)
            .withDimension(dim)
            .build());

    CreateCollectionParam createParam =
        CreateCollectionParam.newBuilder()
            .withCollectionName(collectionName())
            .withDescription("Teacher course-space child chunk vectors")
            .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
            .withShardsNum(2)
            .withFieldTypes(fields)
            .build();
    R<RpcStatus> resp = c.createCollection(createParam);
    ensureSuccess(resp, "createCollection");
    log.info("[Milvus] created collection {} with dim={}", collectionName(), dim);
  }

  private void createVectorIndex(MilvusServiceClient c) {
    CreateIndexParam indexParam =
        CreateIndexParam.newBuilder()
            .withCollectionName(collectionName())
            .withFieldName("vector")
            .withIndexName("idx_vector_hnsw")
            .withIndexType(IndexType.HNSW)
            .withMetricType(MetricType.COSINE)
            .withExtraParam("{\"M\":16,\"efConstruction\":200}")
            .withSyncMode(Boolean.TRUE)
            .withSyncWaitingInterval(MILVUS_WAIT_INTERVAL_SECONDS)
            .withSyncWaitingTimeout(MILVUS_WAIT_TIMEOUT_SECONDS)
            .build();
    R<RpcStatus> resp = c.createIndex(indexParam);
    ensureSuccess(resp, "createIndex");
    log.info("[Milvus] created vector index on {}", collectionName());
  }

  private void flushAndLoad(MilvusServiceClient c) {
    R<?> flushResp =
        c.flush(
            FlushParam.newBuilder()
                .addCollectionName(collectionName())
                .withSyncFlush(Boolean.TRUE)
                .withSyncFlushWaitingInterval(MILVUS_WAIT_INTERVAL_SECONDS)
                .withSyncFlushWaitingTimeout(MILVUS_WAIT_TIMEOUT_SECONDS)
                .build());
    ensureSuccess(flushResp, "flush");
    loadCollection(c);
  }

  private void loadCollection(MilvusServiceClient c) {
    R<RpcStatus> resp =
        c.loadCollection(
            LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName())
                .withSyncLoad(Boolean.TRUE)
                .withSyncLoadWaitingInterval(MILVUS_WAIT_INTERVAL_SECONDS)
                .withSyncLoadWaitingTimeout(MILVUS_WAIT_TIMEOUT_SECONDS)
                .build());
    ensureSuccess(resp, "loadCollection");
  }

  private MilvusServiceClient getClient() {
    if (client == null) {
      synchronized (this) {
        if (client == null) {
          ConnectParam connectParam =
              ConnectParam.newBuilder()
                  .withHost(props.milvus().host())
                  .withPort(props.milvus().port())
                  .build();
          client = new MilvusServiceClient(connectParam);
          log.info("[Milvus] connected to {}:{}", props.milvus().host(), props.milvus().port());
        }
      }
    }
    return client;
  }

  private String collectionName() {
    return props.milvus().collection();
  }

  private int embeddingDimension() {
    if (props.dashscope() != null && props.dashscope().embeddingDimensions() > 0) {
      return props.dashscope().embeddingDimensions();
    }
    return 1024;
  }

  private boolean isSuccess(R<?> response) {
    return response != null && response.getStatus() == R.Status.Success.getCode();
  }

  private void ensureSuccess(R<?> response, String action) {
    if (!isSuccess(response)) {
      String message = response == null ? "null response" : response.getMessage();
      throw new RuntimeException("[Milvus] " + action + " failed: " + message);
    }
  }

  private long toLong(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private String toStringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  @PreDestroy
  void close() {
    if (client != null) {
      client.close();
    }
  }
}
