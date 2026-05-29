package com.tap.backend.academic.leetcode.recommendation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RecommendationFeedbackContext {

    private final Map<Long, Double> scoreAdjustments = new HashMap<>();
    private final Set<Long> dislikedProblemIds = new HashSet<>();
    private final Set<Long> completedProblemIds = new HashSet<>();

    public Map<Long, Double> scoreAdjustments() {
        return scoreAdjustments;
    }

    public Set<Long> dislikedProblemIds() {
        return dislikedProblemIds;
    }

    public Set<Long> completedProblemIds() {
        return completedProblemIds;
    }
}
