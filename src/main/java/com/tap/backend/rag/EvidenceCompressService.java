package com.tap.backend.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EvidenceCompressService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCompressService.class);
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？；\\n]+");
    private static final int DEFAULT_CONTEXT_WINDOW = 8000;

    private final RagProperties ragProps;

    public EvidenceCompressService(RagProperties ragProps) {
        this.ragProps = ragProps;
    }

    public record CompressedEvidence(List<ScoredSentence> sentences, int totalTokens) {}

    public record ScoredSentence(String text, double score, int tokenCount,
                                 long chunkId, String chapterPath, String pageRange) {}

    public CompressedEvidence compress(String parentContent, String query,
                                       long chunkId, String chapterPath, String pageRange) {
        if (parentContent == null || parentContent.isBlank()) {
            return new CompressedEvidence(Collections.emptyList(), 0);
        }

        List<String> sentences = Arrays.stream(SENTENCE_SPLIT.split(parentContent))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() > 2)
                .collect(Collectors.toList());
        if (sentences.isEmpty()) {
            return new CompressedEvidence(Collections.emptyList(), 0);
        }

        RagProperties.Evidence cfg = ragProps.evidence();
        int maxSentences = cfg != null ? Math.max(1, cfg.maxSentences()) : 8;
        int minSentences = Math.min(4, maxSentences);
        double tokenRatioMin = cfg != null ? cfg.tokenRatioMin() : 0.25;
        double tokenRatioMax = cfg != null ? cfg.tokenRatioMax() : 0.40;
        int tokenBudgetMin = Math.max(1, (int) Math.round(DEFAULT_CONTEXT_WINDOW * tokenRatioMin));
        int tokenBudgetMax = Math.max(tokenBudgetMin, (int) Math.round(DEFAULT_CONTEXT_WINDOW * tokenRatioMax));

        Set<String> queryTerms = extractTerms(query);
        List<IndexedSentence> scored = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            scored.add(new IndexedSentence(i, sentence, computeTermOverlap(queryTerms, sentence), estimateTokens(sentence)));
        }

        scored.sort(Comparator.comparingDouble(IndexedSentence::score).reversed()
                .thenComparingInt(IndexedSentence::index));

        List<IndexedSentence> selected = new ArrayList<>();
        int totalTokens = 0;
        for (IndexedSentence candidate : scored) {
            if (selected.size() >= maxSentences) {
                break;
            }
            if (totalTokens >= tokenBudgetMin
                    && selected.size() >= minSentences
                    && totalTokens + candidate.tokenCount() > tokenBudgetMax) {
                break;
            }
            selected.add(candidate);
            totalTokens += candidate.tokenCount();
        }

        if (selected.isEmpty()) {
            IndexedSentence first = scored.get(0);
            selected.add(first);
            totalTokens = first.tokenCount();
        }

        if (selected.size() < minSentences) {
            for (IndexedSentence candidate : scored) {
                if (selected.size() >= minSentences) {
                    break;
                }
                if (selected.contains(candidate)) {
                    continue;
                }
                if (totalTokens + candidate.tokenCount() > tokenBudgetMax) {
                    continue;
                }
                selected.add(candidate);
                totalTokens += candidate.tokenCount();
            }
        }

        selected.sort(Comparator.comparingInt(IndexedSentence::index));
        List<ScoredSentence> ordered = selected.stream()
                .map(s -> new ScoredSentence(s.text(), s.score(), s.tokenCount(), chunkId, chapterPath, pageRange))
                .collect(Collectors.toList());

        log.debug("[EvidenceCompress] Selected {} sentences, {} tokens from {} total sentences",
                ordered.size(), totalTokens, sentences.size());
        return new CompressedEvidence(ordered, totalTokens);
    }

    private Set<String> extractTerms(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        String[] tokens = text.split("[\\s\\uFF0C\\u3002\\uFF01\\uFF1F\\u3001\\uFF1B\\uFF1A\\u201C\\u201D\\u2018\\u2019\\uFF08\\uFF09\\u3010\\u3011\\u300A\\u300B\\p{Punct}]+");
        Set<String> terms = new HashSet<>();
        for (String token : tokens) {
            String trimmed = token.trim().toLowerCase();
            if (trimmed.length() > 1) {
                terms.add(trimmed);
                if (trimmed.length() > 2) {
                    for (int i = 0; i < trimmed.length() - 1; i++) {
                        terms.add(trimmed.substring(i, i + 2));
                    }
                }
            }
        }
        return terms;
    }

    private double computeTermOverlap(Set<String> queryTerms, String sentence) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        String lowerSentence = sentence.toLowerCase();
        long matchCount = queryTerms.stream().filter(lowerSentence::contains).count();
        return (double) matchCount / queryTerms.size();
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 1.5));
    }

    private record IndexedSentence(int index, String text, double score, int tokenCount) {}
}
