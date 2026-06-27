package com.tap.backend.service.grading.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorParameterExtractorTest {

    private final ErrorParameterExtractor extractor = new ErrorParameterExtractor();

    @Test
    void extractsArrayBoundsFromLiteral() {
        String code = """
                int main() {
                    int arr[3] = {10, 20, 30};
                    for (int i = 0; i <= 3; i++) { arr[i] = 0; }
                }
                """;
        var params = extractor.extractArrayBounds("i <= 3", code);
        assertEquals(3, params.arraySize());
        assertTrue(params.isInclusive());
    }

    @Test
    void extractsPointerVariables() {
        var params = extractor.extractPointer("int *p; *p = 1;", "int *p;");
        assertEquals("p", params.pointerVar());
        assertEquals("p", params.dereferenceVar());
    }

    @Test
    void extractsArrayLiteralValues() {
        List<String> values = extractor.extractArrayLiteralValues("int arr[] = {5, 6, 7};");
        assertEquals(List.of("5", "6", "7"), values);
    }
}
