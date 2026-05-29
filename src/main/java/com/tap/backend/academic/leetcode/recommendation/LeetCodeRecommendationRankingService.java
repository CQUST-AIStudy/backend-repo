package com.tap.backend.academic.leetcode.recommendation;

import com.tap.backend.academic.dao.LeetCodeProblemDao;
import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.entity.StudentSkillState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LeetCodeRecommendationRankingService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeRecommendationRankingService.class);

    private static final double WEAKNESS_WEIGHT = 0.35;
    private static final double DIFFICULTY_WEIGHT = 0.25;
    private static final double NOVELTY_WEIGHT = 0.15;
    private static final double DIVERSITY_WEIGHT = 0.15;
    private static final double QUALITY_WEIGHT = 0.10;
    private static final double MIN_TOTAL_SCORE = 0.0;
    private static final double MAX_TOTAL_SCORE = 1.2;

    private static final List<String> DIVERSITY_TAGS = Arrays.asList(
            "\u6570\u7ec4",
            "\u5b57\u7b26\u4e32",
            "\u52a8\u6001\u89c4\u5212",
            "\u8d2a\u5fc3",
            "\u56fe",
            "\u6811"
    );

    private final LeetCodeProblemDao problemDao;

    public LeetCodeRecommendationRankingService(LeetCodeProblemDao problemDao) {
        this.problemDao = problemDao;
    }

    public List<LeetCodeRecommendItem> rankRecommendations(
            List<LeetCodeProblem> problems,
            Map<String, StudentSkillState> skillProfile,
            Integer studentId,
            Map<Long, Double> feedbackAdjustments,
            int limit) {
        List<LeetCodeRecommendItem> rankedItems = rankAndScore(problems, skillProfile, studentId, feedbackAdjustments);
        List<LeetCodeRecommendItem> diversifiedItems = diversityRerank(rankedItems, limit);
        List<LeetCodeRecommendItem> finalItems = diversifiedItems.stream().limit(limit).collect(Collectors.toList());
        for (int i = 0; i < finalItems.size(); i++) {
            finalItems.get(i).setRankNo(i + 1);
        }
        return finalItems;
    }

    public List<LeetCodeRecommendItem> fallbackRecommendations(Integer studentId, int limit) {
        logger.warn("Use fallback recommendation strategy for studentId={}", studentId);
        try {
            List<LeetCodeProblem> problems = problemDao.findByPage(0, limit);
            if (problems == null) {
                return new ArrayList<>();
            }

            List<LeetCodeRecommendItem> items = new ArrayList<>();
            for (int i = 0; i < problems.size(); i++) {
                LeetCodeProblem problem = problems.get(i);
                LeetCodeRecommendItem item = new LeetCodeRecommendItem();
                item.setProblemId(problem.getId());
                item.setProblem(problem);
                item.setStudentId(studentId);
                item.setRankNo(i + 1);
                item.setScoreTotal(scale(0.6));
                item.setScoreNeedMatch(scale(0.5));
                item.setScoreDifficultyFit(scale(0.6));
                item.setScoreSuccessProb(scale(0.6));
                item.setScoreNovelty(scale(0.7));
                item.setScoreQuality(scale(0.8));
                item.setReasonText("\u7cfb\u7edf\u63a8\u8350\u7684\u4f18\u8d28\u9898\u76ee\uff0c\u9002\u5408\u5f53\u524d\u5b66\u4e60\u9636\u6bb5\u3002");
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            logger.error("Fallback recommendation strategy also failed", e);
            return new ArrayList<>();
        }
    }

    private List<LeetCodeRecommendItem> rankAndScore(
            List<LeetCodeProblem> problems,
            Map<String, StudentSkillState> skillProfile,
            Integer studentId,
            Map<Long, Double> feedbackAdjustments) {
        List<LeetCodeRecommendItem> items = new ArrayList<>();
        if (problems == null || problems.isEmpty()) {
            return items;
        }

        for (LeetCodeProblem problem : problems) {
            if (problem == null) {
                continue;
            }

            double weaknessScore = calculateWeaknessMatchScore(problem, skillProfile);
            double difficultyScore = calculateDifficultyMatchScore(problem, skillProfile);
            double noveltyScore = calculateNoveltyScore(problem, skillProfile);
            double diversityScore = 0.8;
            double qualityScore = problem.getQualityScore() == null ? 0.8 : problem.getQualityScore().doubleValue();

            double totalScore = WEAKNESS_WEIGHT * weaknessScore
                    + DIFFICULTY_WEIGHT * difficultyScore
                    + NOVELTY_WEIGHT * noveltyScore
                    + DIVERSITY_WEIGHT * diversityScore
                    + QUALITY_WEIGHT * qualityScore;

            if (problem.getId() != null) {
                totalScore += feedbackAdjustments.getOrDefault(problem.getId(), 0.0);
            }
            totalScore = clamp(totalScore, MIN_TOTAL_SCORE, MAX_TOTAL_SCORE);

            LeetCodeRecommendItem item = new LeetCodeRecommendItem();
            item.setProblemId(problem.getId());
            item.setProblem(problem);
            item.setStudentId(studentId);
            item.setScoreTotal(scale(totalScore));
            item.setScoreNeedMatch(scale(weaknessScore));
            item.setScoreDifficultyFit(scale(difficultyScore));
            item.setScoreSuccessProb(scale(difficultyScore));
            item.setScoreNovelty(scale(noveltyScore));
            item.setScoreQuality(scale(qualityScore));
            item.setReasonText(generateReasonText(problem, weaknessScore));
            items.add(item);
        }

        items.sort((left, right) -> right.getScoreTotal().compareTo(left.getScoreTotal()));
        return items;
    }

    private List<LeetCodeRecommendItem> diversityRerank(List<LeetCodeRecommendItem> items, int limit) {
        if (items.size() <= limit) {
            return items;
        }

        List<LeetCodeRecommendItem> result = new ArrayList<>();
        Set<String> usedDifficulties = new HashSet<>();
        Set<String> usedTags = new HashSet<>();

        for (LeetCodeRecommendItem item : items) {
            if (result.size() >= limit) {
                break;
            }

            String difficulty = Optional.ofNullable(item.getProblem()).map(LeetCodeProblem::getDifficulty).orElse("unknown");
            String problemText = buildProblemText(item.getProblem());
            boolean addsDiversity = false;

            if (usedDifficulties.add(difficulty)) {
                addsDiversity = true;
            }

            for (String tag : DIVERSITY_TAGS) {
                if (problemText.contains(tag.toLowerCase(Locale.ROOT)) && usedTags.add(tag)) {
                    addsDiversity = true;
                    break;
                }
            }

            if (addsDiversity || result.size() < limit / 2) {
                result.add(item);
            }
        }

        for (LeetCodeRecommendItem item : items) {
            if (result.size() >= limit) {
                break;
            }
            if (!result.contains(item)) {
                result.add(item);
            }
        }

        return result;
    }

    private double calculateWeaknessMatchScore(LeetCodeProblem problem, Map<String, StudentSkillState> skillProfile) {
        String problemText = buildProblemText(problem);
        double maxWeaknessScore = 0.0;

        for (StudentSkillState skill : skillProfile.values()) {
            String rawTagName = Optional.ofNullable(skill.getTagName()).orElse("");
            String tagName = rawTagName.toLowerCase(Locale.ROOT);
            String englishTag = getEnglishTag(rawTagName).toLowerCase(Locale.ROOT);
            if ((!tagName.isEmpty() && problemText.contains(tagName)) || (!englishTag.isEmpty() && problemText.contains(englishTag))) {
                double masteryScore = getMasteryValue(skill);
                double weaknessScore = Math.max(0.0, (100 - masteryScore) / 100.0);
                maxWeaknessScore = Math.max(maxWeaknessScore, weaknessScore);
            }
        }

        return maxWeaknessScore;
    }

    private double calculateDifficultyMatchScore(LeetCodeProblem problem, Map<String, StudentSkillState> skillProfile) {
        double avgMastery = skillProfile.values().stream().mapToDouble(this::getMasteryValue).average().orElse(50.0);
        String difficulty = Optional.ofNullable(problem.getDifficulty()).orElse("medium").toLowerCase(Locale.ROOT);

        if (avgMastery < 40) {
            return "easy".equals(difficulty) ? 1.0 : ("medium".equals(difficulty) ? 0.3 : 0.1);
        }
        if (avgMastery < 70) {
            return "medium".equals(difficulty) ? 1.0 : ("easy".equals(difficulty) ? 0.7 : 0.4);
        }
        return "hard".equals(difficulty) ? 1.0 : ("medium".equals(difficulty) ? 0.8 : 0.2);
    }

    private double calculateNoveltyScore(LeetCodeProblem problem, Map<String, StudentSkillState> skillProfile) {
        String problemText = buildProblemText(problem);
        int totalAttempts = 0;
        int matchedSkills = 0;

        for (StudentSkillState skill : skillProfile.values()) {
            String rawTagName = Optional.ofNullable(skill.getTagName()).orElse("");
            String tagName = rawTagName.toLowerCase(Locale.ROOT);
            String englishTag = getEnglishTag(rawTagName).toLowerCase(Locale.ROOT);
            if ((!tagName.isEmpty() && problemText.contains(tagName)) || (!englishTag.isEmpty() && problemText.contains(englishTag))) {
                totalAttempts += getAttemptCountValue(skill);
                matchedSkills++;
            }
        }

        if (matchedSkills == 0) {
            return 1.0;
        }

        double avgAttempts = (double) totalAttempts / matchedSkills;
        return Math.max(0.1, 1.0 - (avgAttempts / 10.0));
    }

    private String generateReasonText(LeetCodeProblem problem, double weaknessScore) {
        StringBuilder reason = new StringBuilder();
        if (weaknessScore > 0.6) {
            reason.append("\u9488\u5bf9\u4f60\u7684\u8584\u5f31\u6280\u80fd\u70b9\u8fdb\u884c\u5f3a\u5316\u7ec3\u4e60\u3002");
        }

        String difficulty = Optional.ofNullable(problem.getDifficulty()).orElse("medium").toLowerCase(Locale.ROOT);
        if ("easy".equals(difficulty)) {
            reason.append("\u9002\u5408\u57fa\u7840\u5de9\u56fa\uff0c\u5efa\u8bae\u5148\u638c\u63e1\u57fa\u672c\u601d\u8def\u3002");
        } else if ("medium".equals(difficulty)) {
            reason.append("\u4e2d\u7b49\u96be\u5ea6\uff0c\u9002\u5408\u63d0\u5347\u89e3\u9898\u80fd\u529b\u3002");
        } else if ("hard".equals(difficulty)) {
            reason.append("\u9ad8\u96be\u5ea6\u6311\u6218\uff0c\u6709\u52a9\u4e8e\u7a81\u7834\u6280\u80fd\u74f6\u9888\u3002");
        }

        Integer estimatedMinutes = problem.getEstimatedMinutes();
        if (estimatedMinutes != null) {
            reason.append(String.format("\u9884\u8ba1\u7528\u65f6 %d \u5206\u949f\u3002", estimatedMinutes));
        }
        return reason.toString();
    }

    private String getEnglishTag(String chineseTag) {
        Map<String, String> tagMap = Map.ofEntries(
                Map.entry("\u52a8\u6001\u89c4\u5212", "dynamic programming"),
                Map.entry("\u8d2a\u5fc3", "greedy"),
                Map.entry("\u56de\u6eaf", "backtrack"),
                Map.entry("\u56fe", "graph"),
                Map.entry("\u6811", "tree"),
                Map.entry("\u5806", "heap"),
                Map.entry("\u5e76\u67e5\u96c6", "union find"),
                Map.entry("\u4f4d\u8fd0\u7b97", "bit manipulation"),
                Map.entry("\u6570\u7ec4", "array"),
                Map.entry("\u5b57\u7b26\u4e32", "string"),
                Map.entry("\u94fe\u8868", "linked list"),
                Map.entry("\u54c8\u5e0c\u8868", "hash")
        );
        return tagMap.getOrDefault(chineseTag, chineseTag);
    }

    private BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double getMasteryValue(StudentSkillState skill) {
        if (skill == null || skill.getMasteryScore() == null) {
            return 50.0;
        }
        return skill.getMasteryScore().doubleValue();
    }

    private int getAttemptCountValue(StudentSkillState skill) {
        if (skill == null || skill.getAttemptCount() == null) {
            return 0;
        }
        return skill.getAttemptCount();
    }

    private String buildProblemText(LeetCodeProblem problem) {
        if (problem == null) {
            return "";
        }
        String title = Optional.ofNullable(problem.getTitleMain()).orElse("");
        String text = Optional.ofNullable(problem.getProblemText()).orElse("");
        String solution = Optional.ofNullable(problem.getSolutionText()).orElse("");
        return (title + " " + text + " " + solution).toLowerCase(Locale.ROOT);
    }
}
