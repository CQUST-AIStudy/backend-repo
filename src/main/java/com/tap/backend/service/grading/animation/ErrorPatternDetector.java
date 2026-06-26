package com.tap.backend.service.grading.animation;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 根据 anchor_text 和 note 识别错误类型。
 */
@Component
public class ErrorPatternDetector {

    public enum ErrorType {
        ARRAY_BOUNDS,
        INVALID_POINTER,
        INFINITE_LOOP,
        MEMORY_LEAK,
        RECURSION,
        RUNTIME_ERROR,
        TYPE_ERROR,
        LOGIC_ERROR,
        RESULT_MISMATCH,
        CONCEPT,
        GENERIC_HIGHLIGHT
    }

    public ErrorType detect(String anchorText, String note, String evidenceKind) {
        String combined = ((anchorText == null ? "" : anchorText) + " " + (note == null ? "" : note))
                .toLowerCase(Locale.ROOT);

        if (containsAny(combined, "越界", "out of bounds", "arr[", "i <=", "访问", "下标")) {
            return ErrorType.ARRAY_BOUNDS;
        }
        if (containsAny(combined, "未初始化", "空指针", "null pointer", "野指针", "解引用", "*temp", "悬挂指针")) {
            return ErrorType.INVALID_POINTER;
        }
        if (containsAny(combined, "死循环", "无限循环", "while(true)", "循环终止", "无法退出")) {
            return ErrorType.INFINITE_LOOP;
        }
        if (containsAny(combined, "内存泄漏", "memory leak", "malloc", "free", "未释放")) {
            return ErrorType.MEMORY_LEAK;
        }
        if (containsAny(combined, "栈溢出", "stack overflow") ||
                (containsAny(combined, "递归", "recursion") && containsAny(combined,
                        "终止条件", "深度", "缺少", "自己调用", "无限递归", "fib(", "fact("))) {
            return ErrorType.RECURSION;
        }
        if (containsAny(combined, "运行时", "runtime error", "异常终止", "崩溃", "segmentation", "segfault")) {
            return ErrorType.RUNTIME_ERROR;
        }
        if (containsAny(combined, "类型", "type mismatch", "类型转换")) {
            return ErrorType.TYPE_ERROR;
        }
        if (containsAny(combined, "逻辑", "logic error", "条件错误")) {
            return ErrorType.LOGIC_ERROR;
        }

        // 结果分析类：明确涉及输出/数据不匹配
        if (containsAny(combined, "结果不符", "输出不符", "数据不符", "图表不符", "预期不符",
                "结果错误", "输出错误", "数据错误", "结果不匹配", "输出不匹配",
                "结果与预期", "输出与预期", "数据与预期",
                "不匹配", "有差异", "不一致")) {
            return ErrorType.RESULT_MISMATCH;
        }

        // 概念/原理类：文字描述或 vlm 证据中包含明确知识点关键词
        if (("text".equalsIgnoreCase(evidenceKind) || "vlm".equalsIgnoreCase(evidenceKind))
                && containsAny(combined, "递归", "指针", "链表", "树", "图", "复杂度",
                        "原理", "内存", "概念", "理解", "算法")) {
            return ErrorType.CONCEPT;
        }

        return ErrorType.GENERIC_HIGHLIGHT;
    }

    public boolean isCodeError(ErrorType type) {
        return type == ErrorType.ARRAY_BOUNDS
                || type == ErrorType.INVALID_POINTER
                || type == ErrorType.INFINITE_LOOP
                || type == ErrorType.MEMORY_LEAK
                || type == ErrorType.RECURSION
                || type == ErrorType.RUNTIME_ERROR
                || type == ErrorType.TYPE_ERROR
                || type == ErrorType.LOGIC_ERROR;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
