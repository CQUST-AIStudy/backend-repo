package com.tap.backend.rag;

import com.tap.backend.domain.rag.QaLogEntity;
import com.tap.backend.repo.QaLogRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class RagAnalyticsService {

    private final QaLogRepository qaLogRepo;

    public RagAnalyticsService(QaLogRepository qaLogRepo) {
        this.qaLogRepo = qaLogRepo;
    }

    public record QuestionRank(String query, long count) {}
    public record FeedbackStats(long thumbsUp, long thumbsDown, long total) {}
    public record GapAlert(String query, long count, double avgCoverage) {}

    public List<QuestionRank> getHotQuestions(Long courseSpaceId, int top) {
        List<Object[]> rows = qaLogRepo.findHotQuestions(courseSpaceId);
        return rows.stream()
                .limit(top)
                .map(r -> new QuestionRank((String) r[0], (Long) r[1]))
                .collect(Collectors.toList());
    }

    public double getHitRate(Long courseSpaceId, double threshold) {
        long total = qaLogRepo.countByCourseSpaceId(courseSpaceId);
        if (total == 0) return 0.0;
        long hits = qaLogRepo.countByCourseSpaceIdAndCoverageScoreGreaterThan(courseSpaceId, threshold);
        return (double) hits / total;
    }

    public Map<String, Long> getCitationCoverage(Long courseSpaceId) {
        List<QaLogEntity> logs = qaLogRepo.findAllByCourseSpaceId(courseSpaceId);
        Map<String, Long> docCounts = new HashMap<>();
        for (QaLogEntity log : logs) {
            if (log.getCitationsJson() != null && !log.getCitationsJson().isBlank()) {
                // Simple parse: count docName occurrences
                try {
                    com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(log.getCitationsJson()).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        String docName = arr.get(i).getAsJsonObject().get("docName").getAsString();
                        docCounts.merge(docName, 1L, Long::sum);
                    }
                } catch (Exception ignored) {}
            }
        }
        return docCounts;
    }

    public double getWebTriggerRate(Long courseSpaceId) {
        long total = qaLogRepo.countByCourseSpaceId(courseSpaceId);
        if (total == 0) return 0.0;
        long webCount = qaLogRepo.countByCourseSpaceIdAndUsedWeb(courseSpaceId, true);
        return (double) webCount / total;
    }

    public FeedbackStats getFeedbackStats(Long courseSpaceId) {
        long up = qaLogRepo.countByCourseSpaceIdAndFeedback(courseSpaceId, 1);
        long down = qaLogRepo.countByCourseSpaceIdAndFeedback(courseSpaceId, -1);
        long total = qaLogRepo.countByCourseSpaceId(courseSpaceId);
        return new FeedbackStats(up, down, total);
    }

    public List<GapAlert> getResourceGaps(Long courseSpaceId, double coverageThreshold, int minFrequency) {
        List<QaLogEntity> logs = qaLogRepo.findAllByCourseSpaceId(courseSpaceId);
        // Group by query, compute avg coverage and count
        Map<String, List<QaLogEntity>> grouped = logs.stream()
                .filter(l -> l.getQuery() != null)
                .collect(Collectors.groupingBy(QaLogEntity::getQuery));

        List<GapAlert> gaps = new ArrayList<>();
        for (Map.Entry<String, List<QaLogEntity>> entry : grouped.entrySet()) {
            List<QaLogEntity> group = entry.getValue();
            if (group.size() < minFrequency) continue;
            double avgCoverage = group.stream()
                    .filter(l -> l.getCoverageScore() != null)
                    .mapToDouble(QaLogEntity::getCoverageScore)
                    .average().orElse(1.0);
            if (avgCoverage < coverageThreshold) {
                gaps.add(new GapAlert(entry.getKey(), group.size(), avgCoverage));
            }
        }
        gaps.sort(Comparator.comparingLong(GapAlert::count).reversed());
        return gaps;
    }
}
