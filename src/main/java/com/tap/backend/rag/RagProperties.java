package com.tap.backend.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tap.rag")
public record RagProperties(
    DashScope dashscope,
    Langchain4j langchain4j,
    Milvus milvus,
    Retrieval retrieval,
    Fusion fusion,
    Rerank rerank,
    Mmr mmr,
    Coverage coverage,
    Evidence evidence,
    Web web,
    Lucene lucene
) {
    public record Langchain4j(
        boolean enabled,
        boolean useStreaming,
        boolean useEmbeddingModel,
        Chat chat,
        Embedding embedding
    ) {
        public Langchain4j {
            if (chat == null) {
                chat = new Chat(null, null, null, 120);
            }
            if (embedding == null) {
                embedding = new Embedding(null, null, null, 0, 30);
            }
        }
    }

    public record Chat(
        String baseUrl,
        String apiKey,
        String modelName,
        int timeoutSeconds
    ) {
        public Chat {
            if (timeoutSeconds == 0) timeoutSeconds = 120;
        }
    }

    public record Embedding(
        String baseUrl,
        String apiKey,
        String modelName,
        int dimensions,
        int timeoutSeconds
    ) {
        public Embedding {
            if (timeoutSeconds == 0) timeoutSeconds = 30;
        }
    }

    public record DashScope(
        String apiKey,
        String baseUrl,
        String embeddingModel,
        int embeddingDimensions
    ) {}

    public record Milvus(
        String host,
        int port,
        String collection
    ) {}

    public record Retrieval(
        int topK,
        int topParent,
        double scoreThreshold
    ) {}

    public record Fusion(
        double alpha,
        double beta,
        double gamma,
        double delta
    ) {
        public Fusion {
            if (alpha == 0 && beta == 0 && gamma == 0 && delta == 0) {
                alpha = 0.5; beta = 0.3; gamma = 0.1; delta = 0.1;
            }
        }
    }

    public record Mmr(
        double lambda
    ) {
        public Mmr {
            if (lambda == 0) lambda = 0.7;
        }
    }

    public record Rerank(
        boolean enabled,
        String provider,
        String endpoint,
        int timeoutMs,
        double crossEncoderWeight,
        int topN,
        double baseWeight,
        double overlapWeight,
        double phraseWeight,
        double annotationWeight
    ) {
        public Rerank {
            if (provider == null || provider.isBlank()) provider = "heuristic";
            if (timeoutMs == 0) timeoutMs = 2000;
            if (crossEncoderWeight == 0) crossEncoderWeight = 0.7;
            if (topN == 0) topN = 5;
            if (baseWeight == 0 && overlapWeight == 0 && phraseWeight == 0 && annotationWeight == 0) {
                baseWeight = 0.45;
                overlapWeight = 0.4;
                phraseWeight = 0.1;
                annotationWeight = 0.05;
            }
        }
    }

    public record Coverage(
        double threshold
    ) {
        public Coverage {
            if (threshold == 0) threshold = 0.4;
        }
    }

    public record Evidence(
        int maxSentences,
        double tokenRatioMin,
        double tokenRatioMax
    ) {
        public Evidence {
            if (maxSentences == 0) maxSentences = 8;
            if (tokenRatioMin == 0) tokenRatioMin = 0.25;
            if (tokenRatioMax == 0) tokenRatioMax = 0.40;
        }
    }

    public record Web(
        String tavilyApiKey,
        boolean enabled
    ) {}

    public record Lucene(
        String indexPath
    ) {}
}
