package com.tap.backend.service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GradingFilenameIdentityParser {

    private static final Pattern STUDENT_NO = Pattern.compile("(?<!\\d)(\\d{6,20})(?!\\d)");

    public FilenameIdentity parse(String filename) {
        if (filename == null || filename.isBlank()) {
            return new FilenameIdentity(null, null);
        }
        String base = filename.replaceFirst("(?i)\\.(pdf|docx?|PDF|DOCX?)$", "").trim();
        List<String> parts = Arrays.stream(base.split("\\s*[-_—–]\\s*"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.size() < 2) {
            return new FilenameIdentity(extractStudentNo(base), null);
        }

        String studentNo = extractStudentNo(parts.get(0));
        String studentName = studentNo != null && parts.size() >= 2 ? parts.get(1) : parts.get(0);
        if (studentName.chars().allMatch(Character::isDigit) || studentName.length() > 64) {
            studentName = null;
        }
        return new FilenameIdentity(studentNo, normalize(studentName));
    }

    private String extractStudentNo(String value) {
        Matcher matcher = STUDENT_NO.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    public record FilenameIdentity(String studentNo, String studentName) {}
}
