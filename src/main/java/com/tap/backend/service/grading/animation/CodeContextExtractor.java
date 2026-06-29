package com.tap.backend.service.grading.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 从证据块内容中提取完整代码上下文。
 */
@Component
public class CodeContextExtractor {

    private static final int CONTEXT_RADIUS = 5;
    private static final Pattern FUNCTION_START =
            Pattern.compile("^\\s*(int|void|char|float|double|bool|struct\\s+\\w+|enum\\s+\\w+)\\s+\\w+\\s*\\(");
    private static final Pattern BLOCK_START = Pattern.compile("\\{");
    private static final Pattern BLOCK_END = Pattern.compile("\\}");

    private static final String[] CODE_KEYWORDS = {
            "for", "while", "if", "else", "switch", "int ", "char ", "float ",
            "double ", "void ", "struct ", "return", "break", "continue",
            "malloc", "free", "printf", "scanf", "arr[", "->", "#include"
    };

    public CodeContext extract(String content, String anchorText) {
        if (content == null || content.isBlank()) {
            return emptyContext();
        }

        List<String> allLines = content.lines().toList();
        if (allLines.isEmpty()) {
            return emptyContext();
        }

        int anchorLine = findLineIndex(allLines, anchorText);
        if (anchorLine < 0) {
            anchorLine = fuzzyFindLineIndex(allLines, anchorText);
        }
        if (anchorLine < 0) {
            // 即使 anchor 没定位到，只要证据块本身是代码，也返回完整代码供展示
            if (looksLikeCode(allLines)) {
                return fullContext(allLines);
            }
            return emptyContext();
        }

        int[] bounds = findFunctionBounds(allLines, anchorLine);
        int contextStart = bounds[0];
        int contextEnd = bounds[1];

        List<String> contextLines = new ArrayList<>(allLines.subList(contextStart, contextEnd + 1));
        return new CodeContext(
                contextLines,
                anchorLine - contextStart + 1,
                anchorLine - contextStart + 1,
                1,
                contextLines.size()
        );
    }

    private int[] findFunctionBounds(List<String> allLines, int anchorLine) {
        int start = Math.max(0, anchorLine - CONTEXT_RADIUS);
        int end = Math.min(allLines.size() - 1, anchorLine + CONTEXT_RADIUS);

        // 向上找函数头
        for (int i = anchorLine; i >= 0; i--) {
            if (FUNCTION_START.matcher(allLines.get(i)).find()) {
                start = i;
                break;
            }
        }

        // 如果找到了函数头，向下匹配大括号直到函数结束
        if (start < anchorLine) {
            int braceDepth = 0;
            boolean entered = false;
            for (int i = start; i < allLines.size(); i++) {
                String line = allLines.get(i);
                braceDepth += countMatches(line, BLOCK_START);
                if (braceDepth > 0) {
                    entered = true;
                }
                braceDepth -= countMatches(line, BLOCK_END);
                if (entered && braceDepth == 0) {
                    end = i;
                    break;
                }
            }
        }

        return new int[]{start, end};
    }

    private int countMatches(String line, Pattern pattern) {
        int count = 0;
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int findLineIndex(List<String> lines, String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private int fuzzyFindLineIndex(List<String> lines, String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String[] parts = text.split("\\s+");
        if (parts.length == 0) {
            return -1;
        }
        String first = parts[0];
        String last = parts[parts.length - 1];
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains(first) && line.contains(last)) {
                return i;
            }
        }
        return -1;
    }

    private CodeContext fullContext(List<String> allLines) {
        // Anchor not found — don't highlight the entire file.
        // Only mark the first line so the UI doesn't put "错误位置" on every line.
        return new CodeContext(
                new ArrayList<>(allLines),
                1,
                1,
                1,
                1
        );
    }

    private boolean looksLikeCode(List<String> lines) {
        int hits = 0;
        for (String line : lines) {
            String lower = line.toLowerCase();
            for (String keyword : CODE_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase())) {
                    hits++;
                    if (hits >= 2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private CodeContext emptyContext() {
        return new CodeContext(List.of(), 0, 0, 0, 0);
    }
}
