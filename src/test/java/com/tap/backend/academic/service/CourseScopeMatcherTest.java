package com.tap.backend.academic.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CourseScopeMatcherTest {

    @Test
    void rejectsRecognizableForeignCourseButKeepsGenericTitles() {
        assertTrue(CourseScopeMatcher.belongsToCourse("\u6570\u636e\u7ed3\u6784", "\u94fe\u8868\u5b9e\u9a8c"));
        assertTrue(CourseScopeMatcher.belongsToCourse("\u6570\u636e\u7ed3\u6784", "\u671f\u4e2d\u6d4b\u8bd5"));
        assertFalse(CourseScopeMatcher.belongsToCourse("\u6570\u636e\u7ed3\u6784", "2025\u7ea7C\u8bed\u8a00\u5b9e\u9a8c4"));
    }
}
