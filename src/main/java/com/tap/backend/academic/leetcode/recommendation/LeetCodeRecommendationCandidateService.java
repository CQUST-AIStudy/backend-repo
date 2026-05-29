package com.tap.backend.academic.leetcode.recommendation;

import com.tap.backend.academic.dao.LeetCodeProblemDao;
import com.tap.backend.academic.dao.LeetCodeProblemTagDao;
import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.entity.StudentSkillState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LeetCodeRecommendationCandidateService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeRecommendationCandidateService.class);

    private static final Set<String> EXPLORATION_TAGS = Set.of(
            "\u52a8\u6001\u89c4\u5212",
            "\u8d2a\u5fc3",
            "\u56de\u6eaf",
            "\u56fe",
            "\u6811",
            "\u5806",
            "\u5e76\u67e5\u96c6",
            "\u4f4d\u8fd0\u7b97"
    );

    private final LeetCodeProblemDao problemDao;
    private final LeetCodeProblemTagDao problemTagDao;

    public LeetCodeRecommendationCandidateService(
            LeetCodeProblemDao problemDao,
            LeetCodeProblemTagDao problemTagDao) {
        this.problemDao = problemDao;
        this.problemTagDao = problemTagDao;
    }

    public Map<Long, LeetCodeProblem> collectCandidates(
            Map<String, StudentSkillState> skillProfile,
            RecommendationFeedbackContext feedbackContext,
            int limit) {
        Map<Long, LeetCodeProblem> candidateById = new LinkedHashMap<>();
        addCandidates(candidateById, recallByWeakness(skillProfile, Math.max(1, (int) (limit * 0.4))));
        addCandidates(candidateById, recallByDifficulty(skillProfile, Math.max(1, (int) (limit * 0.4))));
        addCandidates(candidateById, recallByExploration(skillProfile, Math.max(1, (int) (limit * 0.15))));
        addCandidates(candidateById, recallByPopularity(Math.max(1, (int) (limit * 0.05))));

        Map<Long, LeetCodeProblem> filteredCandidates = applyHistoryFilter(candidateById, feedbackContext, limit);
        int minCandidateCount = Math.max(limit, Math.min(60, limit * 3));
        if (filteredCandidates.size() < minCandidateCount) {
            supplementCandidates(filteredCandidates, minCandidateCount);
        }
        return filteredCandidates;
    }

    private List<LeetCodeProblem> recallByWeakness(Map<String, StudentSkillState> skillProfile, int limit) {
        List<String> weakSkills = skillProfile.values().stream()
                .filter(skill -> getMasteryValue(skill) < 60.0)
                .sorted(Comparator.comparing(this::getMasteryValue))
                .limit(5)
                .map(StudentSkillState::getTagName)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (weakSkills.isEmpty()) {
            weakSkills = skillProfile.values().stream()
                    .sorted(Comparator.comparing(this::getAttemptCountValue))
                    .limit(3)
                    .map(StudentSkillState::getTagName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return findProblemsByTags(weakSkills, limit);
    }

    private List<LeetCodeProblem> recallByDifficulty(Map<String, StudentSkillState> skillProfile, int limit) {
        double avgMastery = skillProfile.values().stream()
                .mapToDouble(this::getMasteryValue)
                .average()
                .orElse(50.0);

        String targetDifficulty = avgMastery < 40 ? "Easy" : (avgMastery < 70 ? "Medium" : "Hard");
        List<LeetCodeProblem> problems = problemDao.findByDifficulty(targetDifficulty);
        if (problems == null) {
            return new ArrayList<>();
        }
        if (problems.isEmpty()) {
            String target = targetDifficulty.toLowerCase(Locale.ROOT);
            problems = problemDao.findAll().stream()
                    .filter(problem -> Optional.ofNullable(problem.getDifficulty())
                            .map(value -> value.toLowerCase(Locale.ROOT))
                            .orElse("")
                            .equals(target))
                    .collect(Collectors.toList());
        }
        return problems.stream().limit(limit).collect(Collectors.toList());
    }

    private List<LeetCodeProblem> recallByExploration(Map<String, StudentSkillState> skillProfile, int limit) {
        List<String> unexploredTags = EXPLORATION_TAGS.stream()
                .filter(tag -> {
                    StudentSkillState state = skillProfile.get(tag);
                    return state == null || getAttemptCountValue(state) < 3;
                })
                .collect(Collectors.toList());
        return findProblemsByTags(unexploredTags, limit);
    }

    private List<LeetCodeProblem> recallByPopularity(int limit) {
        List<LeetCodeProblem> allProblems = problemDao.findAll();
        if (allProblems == null) {
            return new ArrayList<>();
        }
        return allProblems.stream()
                .sorted((left, right) -> {
                    java.math.BigDecimal leftScore = left.getQualityScore() == null ? java.math.BigDecimal.ZERO : left.getQualityScore();
                    java.math.BigDecimal rightScore = right.getQualityScore() == null ? java.math.BigDecimal.ZERO : right.getQualityScore();
                    return rightScore.compareTo(leftScore);
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<LeetCodeProblem> findProblemsByTags(List<String> tags, int limit) {
        if (tags == null || tags.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            List<Long> problemIds = problemTagDao.findProblemIdsByTags("algorithm", tags);
            if (problemIds == null || problemIds.isEmpty()) {
                return findProblemsByTagsTextMatch(tags, limit);
            }

            List<Long> limitedIds = problemIds.stream()
                    .filter(Objects::nonNull)
                    .limit(Math.max(limit, limit * 3L))
                    .collect(Collectors.toList());
            if (limitedIds.isEmpty()) {
                return new ArrayList<>();
            }

            List<LeetCodeProblem> batchProblems = problemDao.findByIds(limitedIds);
            if (batchProblems == null || batchProblems.isEmpty()) {
                return findProblemsByTagsTextMatch(tags, limit);
            }

            Map<Long, LeetCodeProblem> problemById = batchProblems.stream()
                    .filter(Objects::nonNull)
                    .filter(problem -> problem.getId() != null)
                    .collect(Collectors.toMap(LeetCodeProblem::getId, problem -> problem, (left, right) -> left));

            List<LeetCodeProblem> orderedProblems = new ArrayList<>();
            for (Long problemId : limitedIds) {
                LeetCodeProblem problem = problemById.get(problemId);
                if (problem != null) {
                    orderedProblems.add(problem);
                }
                if (orderedProblems.size() >= limit) {
                    break;
                }
            }
            return orderedProblems;
        } catch (Exception e) {
            logger.warn("Failed to find recommendation candidates by tags, fallback to text match: {}", e.getMessage());
            return findProblemsByTagsTextMatch(tags, limit);
        }
    }

    private List<LeetCodeProblem> findProblemsByTagsTextMatch(List<String> tags, int limit) {
        List<LeetCodeProblem> allProblems = problemDao.findAll();
        if (allProblems == null || allProblems.isEmpty()) {
            return new ArrayList<>();
        }

        List<LeetCodeProblem> matchedProblems = new ArrayList<>();
        for (LeetCodeProblem problem : allProblems) {
            String problemText = buildProblemText(problem);
            for (String tag : tags) {
                String normalizedTag = tag.toLowerCase(Locale.ROOT);
                String englishTag = getEnglishTag(tag).toLowerCase(Locale.ROOT);
                if (problemText.contains(normalizedTag) || problemText.contains(englishTag)) {
                    matchedProblems.add(problem);
                    break;
                }
            }
            if (matchedProblems.size() >= limit * 2) {
                break;
            }
        }

        Collections.shuffle(matchedProblems);
        return matchedProblems.stream().limit(limit).collect(Collectors.toList());
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

    private Map<Long, LeetCodeProblem> applyHistoryFilter(
            Map<Long, LeetCodeProblem> candidateById,
            RecommendationFeedbackContext feedbackContext,
            int limit) {
        if (candidateById == null || candidateById.isEmpty() || feedbackContext == null) {
            return candidateById;
        }

        Map<Long, LeetCodeProblem> dislikedFiltered = new LinkedHashMap<>(candidateById);
        dislikedFiltered.keySet().removeIf(feedbackContext.dislikedProblemIds()::contains);

        Map<Long, LeetCodeProblem> completedFiltered = new LinkedHashMap<>(dislikedFiltered);
        completedFiltered.keySet().removeIf(feedbackContext.completedProblemIds()::contains);

        int minKeep = Math.max(3, Math.max(1, limit / 2));
        if (completedFiltered.size() >= minKeep) {
            return completedFiltered;
        }
        if (!dislikedFiltered.isEmpty()) {
            return dislikedFiltered;
        }
        return candidateById;
    }

    private void supplementCandidates(Map<Long, LeetCodeProblem> candidateById, int targetCount) {
        if (candidateById == null || targetCount <= 0) {
            return;
        }

        try {
            int batchSize = Math.max(targetCount * 2, 40);
            addCandidates(candidateById, problemDao.findByPage(0, batchSize));
            if (candidateById.size() >= targetCount) {
                return;
            }
        } catch (Exception e) {
            logger.warn("Failed to supplement recommendation candidates by page: {}", e.getMessage());
        }

        try {
            List<LeetCodeProblem> allProblems = problemDao.findAll();
            if (allProblems != null && !allProblems.isEmpty()) {
                Collections.shuffle(allProblems);
                addCandidates(candidateById, allProblems);
            }
        } catch (Exception e) {
            logger.warn("Failed to supplement recommendation candidates from all problems: {}", e.getMessage());
        }
    }

    private void addCandidates(Map<Long, LeetCodeProblem> candidateById, List<LeetCodeProblem> candidates) {
        if (candidateById == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        for (LeetCodeProblem problem : candidates) {
            if (problem == null || problem.getId() == null) {
                continue;
            }
            candidateById.putIfAbsent(problem.getId(), problem);
        }
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
