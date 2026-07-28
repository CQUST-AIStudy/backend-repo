package com.tap.backend.service.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** CodeDemoComposer 的 resolveStdin 解析用例（从 StudentCodeDemoServiceTest 迁入）。 */
class CodeDemoComposerTest {

    // 依赖仅在 buildDemonstration/autoStdin 时使用；resolveStdin 纯解析，传 null 依赖即可。
    private final CodeDemoComposer composer = new CodeDemoComposer(null, null, null);

    @Test
    void resolveStdinParsesInputSample() {
        assertEquals("3 4", composer.resolveStdin("题面\n输入样例\n3 4\n输出样例\n7\n结束"));
    }

    @Test
    void resolveStdinParsesFencedInputSample() {
        assertEquals("5 6", composer.resolveStdin("输入样例：\n```\n5 6\n```\n输出样例\n11"));
    }

    @Test
    void resolveStdinReturnsEmptyWhenAbsent() {
        assertEquals("", composer.resolveStdin("没有样例的题面"));
    }
}
