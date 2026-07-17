package com.tap.backend.academic.dao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SubmissionMapperContractTest {

    @Test
    void definesUpdateReportStatement() throws IOException {
        try (var stream = getClass().getResourceAsStream("/mappers/SubmissionMapper.xml")) {
            assertTrue(stream != null, "SubmissionMapper.xml should be on the classpath");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(xml.contains("id=\"updateReport\""), "updateReport mapping is missing");
            assertTrue(xml.contains("UPDATE submission"));
            assertTrue(xml.contains("SET report = #{report}"));
            assertTrue(xml.contains("WHERE submission_id = #{submissionId}"));
        }
    }
}
