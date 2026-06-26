package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GradingFilenameIdentityParserTest {

    private final GradingFilenameIdentityParser parser = new GradingFilenameIdentityParser();

    @Test
    void extractsStudentNumberAndNameFromStandardFilename() {
        var result = parser.parse("2023440415-邹名格-指针与数组操作.pdf");

        assertEquals("2023440415", result.studentNo());
        assertEquals("邹名格", result.studentName());
    }

    @Test
    void extractsUniqueNameCandidateWithoutStudentNumber() {
        var result = parser.parse("邹名格-指针与数组操作.pdf");

        assertNull(result.studentNo());
        assertEquals("邹名格", result.studentName());
    }

    @Test
    void rejectsFilenameWithoutDelimitedIdentity() {
        var result = parser.parse("实验报告.pdf");

        assertNull(result.studentNo());
        assertNull(result.studentName());
    }
}
