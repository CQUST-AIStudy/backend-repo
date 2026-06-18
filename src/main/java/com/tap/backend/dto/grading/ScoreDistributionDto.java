package com.tap.backend.dto.grading;

import java.math.BigDecimal;
import java.util.List;

public record ScoreDistributionDto(
        List<ScoreBinDto> bins,
        BigDecimal average,
        Integer highest,
        Integer lowest,
        Integer median,
        Integer count
) {
    public record ScoreBinDto(Integer minInclusive, Integer maxExclusive, Integer count) {}
}
