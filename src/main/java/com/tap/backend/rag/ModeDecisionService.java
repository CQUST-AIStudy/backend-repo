package com.tap.backend.rag;

import org.springframework.stereotype.Component;

@Component
public class ModeDecisionService {

    public record ModeDecision(String effectiveMode, boolean shouldFallbackToWeb, String lowCoverageMessage) {}

    public ModeDecision decide(String requestedMode, String defaultMode, boolean allowWebSearch,
                               double coverageScore, double coverageThreshold) {
        String effectiveMode = requestedMode != null && !requestedMode.isBlank() ? requestedMode : defaultMode;
        if (effectiveMode == null || effectiveMode.isBlank()) {
            effectiveMode = "strict";
        }

        if ("strict".equals(defaultMode) && "open".equals(requestedMode)) {
            effectiveMode = "strict";
        }

        boolean lowCoverage = coverageScore < coverageThreshold;
        boolean shouldWeb = false;
        String message = null;

        if (lowCoverage) {
            if ("strict".equals(effectiveMode)) {
                message = "当前课程资料对这个问题的覆盖不足。请补充课程资料，或切换到开放模式后再试。";
            } else if ("open".equals(effectiveMode) && allowWebSearch) {
                shouldWeb = true;
            }
        }

        return new ModeDecision(effectiveMode, shouldWeb, message);
    }
}
