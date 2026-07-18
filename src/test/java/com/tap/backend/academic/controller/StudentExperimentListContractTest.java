package com.tap.backend.academic.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudentExperimentListContractTest {

    @Test
    void unifiedListPrefersLatestProblemAttemptSubmitTime() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/tap/backend/academic/controller/ApiController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("MAX(spa.submitted_at) AS latest_submit_at"));
        assertTrue(source.contains("latest_attempt.latest_submit_at"));
        assertTrue(source.contains("row[20] != null ? row[20] : (row[11] != null ? row[11] : row[10])"));
    }
}
