package com.tap.backend.summary.dto;

import java.util.List;

public record StructuredSummaryDto(
    String researchProblemMotivation,
    List<String> methods,
    List<String> experimentsData,
    String conclusions,
    List<String> limitationsInsights
) {}
