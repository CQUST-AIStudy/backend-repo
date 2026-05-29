package com.tap.backend.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class FusionRankService {

    private static final Logger log = LoggerFactory.getLogger(FusionRankService.class);
    private final RagProperties ragProps;

    // Doc priority map
    private static final Map<String, Double> DEFAULT_DOC_PRIORITY = Map.of(
            "faq", 1.0,
            "lab_guide", 0.8,
            "textbook", 0.6,
            "ppt", 0.4,
            "other", 0.3
    );

    public FusionRankService(RagProperties ragProps) {
        this.ragProps = ragProps;
    }

    public record RankedParent(long parentId, long docId, double finalScore,
                                String chapterPath, String pageRange, String docType) {}

    public record FusionCandidate(long parentId, long docId, double vecScore, double bm25Score,
                                   String docType, boolean hasAnnotation,
                                   String chapterPath, String pageRange) {}

    /**
     * Merge vector hits and BM25 hits, apply fusion formula, aggregate by parent, then MMR.
     */
    public List<RankedParent> rank(List<MilvusSearchService.SearchHit> vecHits,
                                    List<LuceneBm25Service.Bm25Hit> bm25Hits,
                                    Map<Long, String> chunkAnnotations,
                                    Map<Long, String> docTypeMap,
                                    int topN) {
        double alpha = ragProps.fusion() != null ? ragProps.fusion().alpha() : 0.5;
        double beta = ragProps.fusion() != null ? ragProps.fusion().beta() : 0.3;
        double gamma = ragProps.fusion() != null ? ragProps.fusion().gamma() : 0.1;
        double delta = ragProps.fusion() != null ? ragProps.fusion().delta() : 0.1;
        double mmrLambda = ragProps.mmr() != null ? ragProps.mmr().lambda() : 0.7;
        double scoreThreshold = ragProps.retrieval() != null ? ragProps.retrieval().scoreThreshold() : 0.0;
        float maxVecScore = vecHits.stream().map(MilvusSearchService.SearchHit::score).max(Float::compare).orElse(1.0f);
        float maxBm25Score = bm25Hits.stream().map(LuceneBm25Service.Bm25Hit::score).max(Float::compare).orElse(1.0f);
        if (maxVecScore <= 0) maxVecScore = 1.0f;
        if (maxBm25Score <= 0) maxBm25Score = 1.0f;

        // 1. Build candidate pool keyed by parentId
        Map<Long, FusionCandidate> pool = new LinkedHashMap<>();

        for (MilvusSearchService.SearchHit vh : vecHits) {
            long pid = vh.parentId();
            FusionCandidate existing = pool.get(pid);
            double score = vh.score() / maxVecScore;
            if (existing == null || score > existing.vecScore()) {
                String docType = docTypeMap.getOrDefault(vh.docId(), "other");
                boolean hasAnno = chunkAnnotations.containsKey(vh.parentId()) || chunkAnnotations.containsKey(vh.chunkId());
                pool.put(pid, new FusionCandidate(pid, vh.docId(),
                        score,
                        existing != null ? existing.bm25Score() : 0.0,
                        docType, hasAnno || (existing != null && existing.hasAnnotation()),
                        vh.chapterPath(), vh.pageRange()));
            }
        }

        for (LuceneBm25Service.Bm25Hit bh : bm25Hits) {
            long pid = bh.parentId();
            FusionCandidate existing = pool.get(pid);
            double score = bh.score() / maxBm25Score;
            if (existing == null) {
                String docType = docTypeMap.getOrDefault(bh.docId(), "other");
                boolean hasAnno = chunkAnnotations.containsKey(bh.parentId()) || chunkAnnotations.containsKey(bh.chunkId());
                pool.put(pid, new FusionCandidate(pid, bh.docId(),
                        0.0, score, docType, hasAnno,
                        bh.chapterPath(), bh.pageRange()));
            } else if (score > existing.bm25Score()) {
                pool.put(pid, new FusionCandidate(pid, existing.docId(),
                        existing.vecScore(), score,
                        existing.docType(),
                        existing.hasAnnotation() || chunkAnnotations.containsKey(bh.chunkId()),
                        existing.chapterPath(), existing.pageRange()));
            }
        }

        // 2. Compute fusion scores
        List<ScoredCandidate> scored = new ArrayList<>();
        for (FusionCandidate c : pool.values()) {
            double docPriority = DEFAULT_DOC_PRIORITY.getOrDefault(c.docType(), 0.3);
            double teacherBoost = c.hasAnnotation() ? 1.0 : 0.0;
            double routeBonus = (c.vecScore() > 0.0 && c.bm25Score() > 0.0) ? 0.08 : 0.0;
            double finalScore = alpha * c.vecScore()
                    + beta * c.bm25Score()
                    + gamma * docPriority
                    + delta * teacherBoost
                    + routeBonus;
            if (finalScore >= scoreThreshold) {
                scored.add(new ScoredCandidate(c, finalScore));
            }
        }

        // Sort by finalScore descending
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 3. MMR de-duplication
        List<ScoredCandidate> selected = mmrSelect(scored, topN, mmrLambda);

        return selected.stream()
                .map(sc -> new RankedParent(
                        sc.candidate.parentId(), sc.candidate.docId(), sc.score,
                        sc.candidate.chapterPath(), sc.candidate.pageRange(), sc.candidate.docType()))
                .collect(Collectors.toList());
    }

    /**
     * Compute fusion score for a single candidate (exposed for testing).
     */
    public double computeFusionScore(double vecScore, double bm25Score,
                                      String docType, boolean hasAnnotation) {
        double alpha = ragProps.fusion() != null ? ragProps.fusion().alpha() : 0.5;
        double beta = ragProps.fusion() != null ? ragProps.fusion().beta() : 0.3;
        double gamma = ragProps.fusion() != null ? ragProps.fusion().gamma() : 0.1;
        double delta = ragProps.fusion() != null ? ragProps.fusion().delta() : 0.1;
        double docPriority = DEFAULT_DOC_PRIORITY.getOrDefault(docType, 0.3);
        double teacherBoost = hasAnnotation ? 1.0 : 0.0;
        return alpha * vecScore
                + beta * bm25Score
                + gamma * docPriority
                + delta * teacherBoost;
    }

    /**
     * Get doc priority for a given doc type.
     */
    public double getDocPriority(String docType) {
        return DEFAULT_DOC_PRIORITY.getOrDefault(docType, 0.3);
    }

    private List<ScoredCandidate> mmrSelect(List<ScoredCandidate> candidates, int topN, double lambda) {
        if (candidates.isEmpty()) return Collections.emptyList();

        List<ScoredCandidate> selected = new ArrayList<>();
        List<ScoredCandidate> remaining = new ArrayList<>(candidates);

        // Always pick the top-scored first
        selected.add(remaining.remove(0));

        while (selected.size() < topN && !remaining.isEmpty()) {
            double bestMmr = Double.NEGATIVE_INFINITY;
            int bestIdx = 0;

            for (int i = 0; i < remaining.size(); i++) {
                ScoredCandidate cand = remaining.get(i);
                double maxSim = 0.0;
                for (ScoredCandidate sel : selected) {
                    double sim = computeSimilarity(cand, sel);
                    if (sim > maxSim) maxSim = sim;
                }
                double mmrScore = lambda * cand.score - (1 - lambda) * maxSim;
                if (mmrScore > bestMmr) {
                    bestMmr = mmrScore;
                    bestIdx = i;
                }
            }
            selected.add(remaining.remove(bestIdx));
        }
        return selected;
    }

    /**
     * Simple similarity: same doc → 0.8, same chapter → 0.5, otherwise 0.0
     */
    private double computeSimilarity(ScoredCandidate a, ScoredCandidate b) {
        if (a.candidate.docId() == b.candidate.docId()) {
            String cpA = a.candidate.chapterPath() != null ? a.candidate.chapterPath() : "";
            String cpB = b.candidate.chapterPath() != null ? b.candidate.chapterPath() : "";
            if (cpA.equals(cpB) && !cpA.isEmpty()) return 0.8;
            return 0.5;
        }
        return 0.0;
    }

    private static class ScoredCandidate {
        final FusionCandidate candidate;
        final double score;

        ScoredCandidate(FusionCandidate candidate, double score) {
            this.candidate = candidate;
            this.score = score;
        }
    }
}
