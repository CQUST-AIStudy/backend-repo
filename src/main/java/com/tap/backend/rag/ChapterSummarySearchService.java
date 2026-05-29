package com.tap.backend.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ChapterSummarySearchService {

    private static final Logger log = LoggerFactory.getLogger(ChapterSummarySearchService.class);
    private static final String COLLECTION = "chapter_summaries";
    private static final List<String> OUTPUT_FIELDS = List.of(
            "summary_id", "course_space_id", "doc_id", "chapter_path", "level"
    );

    private final RagProperties props;
    private volatile MilvusServiceClient client;

    public ChapterSummarySearchService(RagProperties props) {
        this.props = props;
    }

    public record SummaryHit(long summaryId, long courseSpaceId, long docId,
                              String chapterPath, int level, float score) {}

    /**
     * Search chapter summaries to find the most relevant chapters for a summary-type query.
     */
    public List<SummaryHit> search(long courseSpaceId, List<Float> queryVector, int topC) {
        try {
            MilvusServiceClient c = getClient();
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withMetricType(MetricType.COSINE)
                    .withTopK(topC)
                    .withVectors(Collections.singletonList(queryVector))
                    .withVectorFieldName("vector")
                    .withOutFields(OUTPUT_FIELDS)
                    .withExpr("course_space_id == " + courseSpaceId)
                    .withParams("{\"nprobe\": 16}")
                    .build();

            R<SearchResults> resp = c.search(searchParam);
            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("[ChapterSummary] search failed: {}", resp.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<SummaryHit> hits = new ArrayList<>();

            for (int i = 0; i < scores.size(); i++) {
                SearchResultsWrapper.IDScore idScore = scores.get(i);
                String chapterPath = (String) wrapper.getFieldData("chapter_path", 0).get(i);
                int level = ((Long) wrapper.getFieldData("level", 0).get(i)).intValue();
                long docId = (Long) wrapper.getFieldData("doc_id", 0).get(i);

                hits.add(new SummaryHit(idScore.getLongID(), courseSpaceId, docId,
                        chapterPath, level, idScore.getScore()));
            }
            return hits;
        } catch (Exception e) {
            log.warn("[ChapterSummary] search error (collection may not exist yet): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private MilvusServiceClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new MilvusServiceClient(ConnectParam.newBuilder()
                            .withHost(props.milvus().host())
                            .withPort(props.milvus().port())
                            .build());
                }
            }
        }
        return client;
    }
}
