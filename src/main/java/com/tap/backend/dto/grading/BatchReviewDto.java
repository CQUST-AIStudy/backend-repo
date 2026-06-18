package com.tap.backend.dto.grading;

import com.tap.backend.domain.grading.GradingTaskEntity;

import java.util.List;

public record BatchReviewDto(
        String status,
        String summary,
        List<String> commonIssues,
        List<String> strengths,
        List<String> teachingAdvice,
        ScoreDistributionDto scoreDistribution
) {
    public static BatchReviewDto pending() {
        return new BatchReviewDto(GradingTaskEntity.BatchReviewStatus.PENDING.name(),
                null, null, null, null, null);
    }
}
