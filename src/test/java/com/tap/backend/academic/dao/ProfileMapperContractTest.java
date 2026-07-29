package com.tap.backend.academic.dao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProfileMapperContractTest {

    @Test
    void classProfileQueriesUseCanonicalClassMembership() throws IOException {
        try (var stream = getClass().getResourceAsStream("/mappers/ProfileMapper.xml")) {
            assertTrue(stream != null, "ProfileMapper.xml should be on the classpath");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(xml.contains("test=\"classId != null\""));
            assertTrue(xml.contains("FROM class_member cm"));
            assertTrue(xml.contains("cm.class_id = #{classId}"));
            assertTrue(xml.contains("sp.student_no COLLATE utf8mb4_unicode_ci = ss.student_id"));
        }
    }
}
