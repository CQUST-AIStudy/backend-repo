package com.tap.backend.service.grading.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeContextExtractorTest {

    private final CodeContextExtractor extractor = new CodeContextExtractor();

    @Test
    void extractsFunctionBounds() {
        String code = """
                int helper() { return 0; }
                int main() {
                    int arr[3] = {1, 2, 3};
                    for (int i = 0; i <= 3; i++) {
                        printf("%d", arr[i]);
                    }
                    return 0;
                }
                """;
        CodeContext ctx = extractor.extract(code, "i <= 3");
        assertNotNull(ctx);
        String full = ctx.fullCode();
        assertTrue(full.contains("int main()"));
        assertTrue(full.contains("return 0"));
        assertEquals(3, ctx.relativeAnchorLine());
    }

    @Test
    void returnsEmptyContextForNonCodeContent() {
        CodeContext ctx = extractor.extract("这是一段纯文字说明，没有代码。", "not found");
        assertNotNull(ctx);
        assertEquals(0, ctx.fullLines().size());
    }

    @Test
    void fallsBackToFullCodeBlockWhenAnchorMissing() {
        CodeContext ctx = extractor.extract("""
                int main() {
                    printf("hello");
                    return 0;
                }
                """, "not found");
        assertNotNull(ctx);
        assertEquals(4, ctx.fullLines().size());
        assertTrue(ctx.fullCode().contains("int main()"));
        assertEquals(1, ctx.relativeAnchorLine());
    }
}
