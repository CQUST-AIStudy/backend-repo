package com.tap.backend.academic.service;

import java.util.List;
import java.util.Locale;

/** Matches recognizable course subjects while allowing generic assignment titles. */
public final class CourseScopeMatcher {

    private static final List<List<String>> COURSE_SUBJECT_ALIASES = List.of(
            List.of("\u6570\u636e\u7ed3\u6784"),
            List.of("C\u8bed\u8a00", "C\u7a0b\u5e8f\u8bbe\u8ba1"),
            List.of("Java"),
            List.of("Python"),
            List.of("\u8ba1\u7b97\u673a\u7f51\u7edc"),
            List.of("\u64cd\u4f5c\u7cfb\u7edf"),
            List.of("\u6570\u636e\u5e93"),
            List.of("\u8f6f\u4ef6\u5de5\u7a0b"),
            List.of("\u8ba1\u7b97\u673a\u7ec4\u6210")
    );

    private CourseScopeMatcher() {
    }

    public static boolean belongsToCourse(Object courseValue, Object assignmentValue) {
        int courseSubject = detectSubject(normalize(courseValue));
        int assignmentSubject = detectSubject(normalize(assignmentValue));
        return courseSubject < 0 || assignmentSubject < 0 || courseSubject == assignmentSubject;
    }

    private static int detectSubject(String value) {
        if (value == null) {
            return -1;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < COURSE_SUBJECT_ALIASES.size(); i++) {
            for (String alias : COURSE_SUBJECT_ALIASES.get(i)) {
                if (normalized.contains(alias.toLowerCase(Locale.ROOT))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
