package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class RagDocumentProcessorTest {

    private final RagDocumentProcessor processor =
            new RagDocumentProcessor(null, null, null, null, null, null, null, null);

    @Test
    void detectChapterRecognizesChineseChapterTitle() throws Exception {
        Method method = RagDocumentProcessor.class.getDeclaredMethod("detectChapter", String.class);
        method.setAccessible(true);
        String chapter = (String) method.invoke(processor, "第一章 栈与队列\n这里是正文");
        assertEquals("第一章 栈与队列", chapter);
    }

    @Test
    void normalizeExtractedTextPreservesPageBreaks() throws Exception {
        Method method = RagDocumentProcessor.class.getDeclaredMethod("normalizeExtractedText", String.class);
        method.setAccessible(true);
        String normalized = (String) method.invoke(processor, "第一页内容\r\n\f\r\n第二页内容");
        assertTrue(normalized.contains("\f"));
    }
}
