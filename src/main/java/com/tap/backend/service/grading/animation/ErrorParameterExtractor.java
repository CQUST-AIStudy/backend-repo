package com.tap.backend.service.grading.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 从真实代码片段中提取错误相关参数。
 */
@Component
public class ErrorParameterExtractor {

    private static final Pattern ARRAY_SIZE_PATTERN =
            Pattern.compile("\\[\\s*(\\d+)\\s*\\]|大小为(\\d+)|长度是(\\d+)");
    private static final Pattern LOOP_BOUND_PATTERN =
            Pattern.compile("<=\\s*(\\w+|\\d+)|<\\s*(\\w+|\\d+)|>=\\s*(\\w+|\\d+)|>\\s*(\\w+|\\d+)");
    private static final Pattern ARRAY_LITERAL_PATTERN = Pattern.compile("\\{\\s*([^}]+)\\s*\\}");
    private static final Pattern POINTER_DECL_PATTERN = Pattern.compile("(\\w+)\\s*\\*\\s*(\\w+)");
    private static final Pattern DEREF_PATTERN = Pattern.compile("\\*\\s*(\\w+)");

    public ArrayBoundsParams extractArrayBounds(String anchorText, String contextCode) {
        int arraySize = 5;
        int loopUpperBound = 5;
        boolean isInclusive = anchorText != null && anchorText.contains("<=");

        if (anchorText != null) {
            Matcher sizeMatcher = ARRAY_SIZE_PATTERN.matcher(anchorText);
            if (sizeMatcher.find()) {
                arraySize = parseIntOrDefault(firstNonNull(
                        sizeMatcher.group(1), sizeMatcher.group(2), sizeMatcher.group(3)), arraySize);
            }

            Matcher boundMatcher = LOOP_BOUND_PATTERN.matcher(anchorText);
            if (boundMatcher.find()) {
                String bound = firstNonNull(boundMatcher.group(1), boundMatcher.group(2),
                        boundMatcher.group(3), boundMatcher.group(4));
                if (bound != null && bound.matches("\\d+")) {
                    loopUpperBound = Integer.parseInt(bound);
                } else {
                    loopUpperBound = arraySize;
                }
            }
        }

        if (contextCode != null) {
            Matcher literalMatcher = ARRAY_LITERAL_PATTERN.matcher(contextCode);
            if (literalMatcher.find()) {
                String[] values = literalMatcher.group(1).split(",");
                arraySize = values.length;
            }
        }

        return new ArrayBoundsParams(arraySize, loopUpperBound, isInclusive);
    }

    public PointerParams extractPointer(String anchorText, String contextCode) {
        String pointerVar = null;
        String dereferenceVar = null;

        if (anchorText != null) {
            Matcher decl = POINTER_DECL_PATTERN.matcher(anchorText);
            if (decl.find()) {
                pointerVar = decl.group(2);
            }
            Matcher deref = DEREF_PATTERN.matcher(anchorText);
            if (deref.find()) {
                dereferenceVar = deref.group(1);
            }
        }

        if (pointerVar == null && contextCode != null) {
            Matcher decl = POINTER_DECL_PATTERN.matcher(contextCode);
            if (decl.find()) {
                pointerVar = decl.group(2);
            }
        }
        if (dereferenceVar == null && contextCode != null) {
            Matcher deref = DEREF_PATTERN.matcher(contextCode);
            if (deref.find()) {
                dereferenceVar = deref.group(1);
            }
        }

        if (pointerVar == null) pointerVar = "ptr";
        if (dereferenceVar == null) dereferenceVar = pointerVar;
        return new PointerParams(pointerVar, dereferenceVar);
    }

    public LoopParams extractLoop(String anchorText) {
        String condition = anchorText == null ? "" : anchorText.trim();
        return new LoopParams(condition);
    }

    public List<String> extractArrayLiteralValues(String contextCode) {
        List<String> values = new ArrayList<>();
        if (contextCode == null) {
            return values;
        }
        Matcher matcher = ARRAY_LITERAL_PATTERN.matcher(contextCode);
        if (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                values.add(part.trim());
            }
        }
        if (values.isEmpty()) {
            for (int i = 1; i <= 5; i++) {
                values.add(String.valueOf(i * 10));
            }
        }
        return values;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    public record ArrayBoundsParams(int arraySize, int loopUpperBound, boolean isInclusive) {}
    public record PointerParams(String pointerVar, String dereferenceVar) {}
    public record LoopParams(String condition) {}
}
