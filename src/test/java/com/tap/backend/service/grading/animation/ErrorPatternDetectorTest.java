package com.tap.backend.service.grading.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tap.backend.service.grading.animation.ErrorPatternDetector.ErrorType;
import org.junit.jupiter.api.Test;

class ErrorPatternDetectorTest {

    private final ErrorPatternDetector detector = new ErrorPatternDetector();

    @Test
    void detectsArrayBounds() {
        assertEquals(ErrorType.ARRAY_BOUNDS,
                detector.detect("for (i = 0; i <= n; i++)", "数组越界访问", "ocr"));
        assertEquals(ErrorType.ARRAY_BOUNDS,
                detector.detect("arr[10]", "index out of bounds", "ocr"));
    }

    @Test
    void detectsPointerError() {
        assertEquals(ErrorType.INVALID_POINTER,
                detector.detect("int *p; *p = 1;", "使用了未初始化的指针", "ocr"));
        assertEquals(ErrorType.INVALID_POINTER,
                detector.detect("p = NULL", "空指针解引用", "ocr"));
    }

    @Test
    void detectsRuntimeError() {
        assertEquals(ErrorType.RUNTIME_ERROR,
                detector.detect("divide", "运行时错误：除零", "ocr"));
    }

    @Test
    void detectsResultMismatch() {
        assertEquals(ErrorType.RESULT_MISMATCH,
                detector.detect("output", "实际输出与预期不符", "text"));
    }

    @Test
    void detectsConcept() {
        assertEquals(ErrorType.CONCEPT,
                detector.detect("递归", "请理解递归原理", "text"));
    }

    @Test
    void detectsInfiniteLoop() {
        assertEquals(ErrorType.INFINITE_LOOP,
                detector.detect("while (1)", "死循环", "ocr"));
    }

    @Test
    void detectsMemoryLeak() {
        assertEquals(ErrorType.MEMORY_LEAK,
                detector.detect("malloc", "没有 free 造成内存泄漏", "ocr"));
    }

    @Test
    void genericHighlightForUnrelatedText() {
        assertEquals(ErrorType.GENERIC_HIGHLIGHT, detector.detect("", "代码规范", "text"));
    }
}
