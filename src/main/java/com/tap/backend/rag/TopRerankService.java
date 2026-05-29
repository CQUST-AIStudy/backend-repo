package com.tap.backend.rag;

import com.tap.backend.domain.rag.DocChunkEntity;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class TopRerankService {

    private static final Logger log = LoggerFactory.getLogger(TopRerankService.class);

    private final RagProperties ragProps;
    private final CrossEncoderRerankClient crossEncoderClient;

    public TopRerankService(RagProperties ragProps,
                            CrossEncoderRerankClient crossEncoderClient) {
        this.ragProps = ragProps;
        this.crossEncoderClient = crossEncoderClient;
    }

    public List<FusionRankService.RankedParent> rerank(
            String query,
            List<FusionRankService.RankedParent> ranked,
            Map<Long, DocChunkEntity> parentChunkMap,
            Map<Long, String> chunkAnnotations) {
        if (ranked == null || ranked.isEmpty()) return Collections.emptyList();
        RagProperties.Rerank cfg = ragProps.rerank();
        if (cfg == null || !cfg.enabled()) {
            return ranked;
        }

        double maxBase = ranked.stream()
                .mapToDouble(FusionRankService.RankedParent::finalScore)
                .max()
                .orElse(1.0);
        if (maxBase <= 0) maxBase = 1.0;

        Map<Long, Double> heuristicScores = new HashMap<>(ranked.size());
        Set<String> terms = extractTerms(query);
        for (FusionRankService.RankedParent rp : ranked) {
            DocChunkEntity chunk = parentChunkMap.get(rp.parentId());
            String content = chunk != null && chunk.getContent() != null ? chunk.getContent() : "";
            String chapter = rp.chapterPath() != null ? rp.chapterPath() : "";

            double baseNorm = rp.finalScore() / maxBase;
            double overlap = computeOverlap(terms, content);
            double phrase = hasPhrase(query, content, chapter) ? 1.0 : 0.0;
            double anno = chunkAnnotations.containsKey(rp.parentId()) ? 1.0 : 0.0;

            double heuristic = cfg.baseWeight() * baseNorm
                    + cfg.overlapWeight() * overlap
                    + cfg.phraseWeight() * phrase
                    + cfg.annotationWeight() * anno;
            heuristicScores.put(rp.parentId(), heuristic);
        }

        Map<Long, Double> crossScoresNorm = Collections.emptyMap();
        if ("cross_encoder_http".equalsIgnoreCase(cfg.provider())) {
            List<CrossEncoderRerankClient.Candidate> candidates = ranked.stream()
                    .map(rp -> {
                        DocChunkEntity c = parentChunkMap.get(rp.parentId());
                        String content = c != null && c.getContent() != null ? c.getContent() : "";
                        String chapter = rp.chapterPath() != null ? rp.chapterPath() : "";
                        String text = chapter.isBlank() ? content : (chapter + "\n" + content);
                        return new CrossEncoderRerankClient.Candidate(rp.parentId(), text);
                    })
                    .collect(Collectors.toList());
            Map<Long, Double> crossScores = crossEncoderClient.rerank(query, candidates, cfg.topN());
            crossScoresNorm = normalizeScores(crossScores);
            if (!crossScoresNorm.isEmpty()) {
                log.debug("[RAG] cross-encoder rerank applied, size={}", crossScoresNorm.size());
            } else {
                log.debug("[RAG] cross-encoder rerank unavailable, fallback to heuristic");
            }
        }

        double ceWeight = Math.max(0.0, Math.min(1.0, cfg.crossEncoderWeight()));
        List<Scored> scored = new ArrayList<>(ranked.size());
        for (FusionRankService.RankedParent rp : ranked) {
            double heuristic = heuristicScores.getOrDefault(rp.parentId(), 0.0);
            double finalScore = heuristic;
            if (!crossScoresNorm.isEmpty() && crossScoresNorm.containsKey(rp.parentId())) {
                double ce = crossScoresNorm.get(rp.parentId());
                finalScore = (1.0 - ceWeight) * heuristic + ceWeight * ce;
            }
            scored.add(new Scored(rp, finalScore));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int limit = Math.min(cfg.topN(), scored.size());
        return scored.subList(0, limit).stream().map(s -> s.parent).collect(Collectors.toList());
    }

    private Set<String> extractTerms(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        String[] tokens = text.toLowerCase().split("[\\s\\uFF0C\\u3002\\uFF01\\uFF1F\\u3001\\uFF1B\\uFF1A\\p{Punct}]+");
        Set<String> terms = new HashSet<>();
        for (String t : tokens) {
            String x = t.trim();
            if (x.length() >= 2) {
                terms.add(x);
                if (x.length() > 2) {
                    for (int i = 0; i < x.length() - 1; i++) {
                        terms.add(x.substring(i, i + 2));
                    }
                }
            }
        }
        return terms;
    }

    private double computeOverlap(Set<String> terms, String content) {
        if (terms.isEmpty() || content == null || content.isBlank()) return 0.0;
        String lc = content.toLowerCase();
        long hit = terms.stream().filter(lc::contains).count();
        return (double) hit / terms.size();
    }

    private boolean hasPhrase(String query, String content, String chapterPath) {
        if (query == null || query.isBlank()) return false;
        String q = query.trim().toLowerCase();
        if (q.length() < 3) return false;
        return (content != null && content.toLowerCase().contains(q))
                || (chapterPath != null && chapterPath.toLowerCase().contains(q));
    }

    private Map<Long, Double> normalizeScores(Map<Long, Double> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        double min = raw.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        Map<Long, Double> norm = new HashMap<>(raw.size());
        if (max - min < 1e-9) {
            for (Long id : raw.keySet()) {
                norm.put(id, 1.0);
            }
            return norm;
        }
        for (Map.Entry<Long, Double> e : raw.entrySet()) {
            norm.put(e.getKey(), (e.getValue() - min) / (max - min));
        }
        return norm;
    }

    private record Scored(FusionRankService.RankedParent parent, double score) {}
}
