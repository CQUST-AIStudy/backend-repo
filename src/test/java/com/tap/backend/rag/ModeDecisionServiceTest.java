package com.tap.backend.rag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModeDecisionServiceTest {

    private final ModeDecisionService service = new ModeDecisionService();

    @Test
    void strictModeLowCoverageReturnsWarningMessage() {
        ModeDecisionService.ModeDecision decision = service.decide("strict", "strict", false, 0.12, 0.30);
        assertNotNull(decision.lowCoverageMessage());
        assertFalse(decision.lowCoverageMessage().isBlank());
        assertFalse(decision.shouldFallbackToWeb());
    }

    @Test
    void openModeLowCoverageCanFallbackToWeb() {
        ModeDecisionService.ModeDecision decision = service.decide("open", "open", true, 0.12, 0.30);
        assertTrue(decision.shouldFallbackToWeb());
    }
}
