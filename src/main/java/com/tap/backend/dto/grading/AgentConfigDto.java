package com.tap.backend.dto.grading;

import java.math.BigDecimal;

public record AgentConfigDto(
        Long id,
        String code,
        String name,
        String promptTemplate,
        String model,
        BigDecimal temperature,
        int maxTokens,
        boolean enabled
) {}
