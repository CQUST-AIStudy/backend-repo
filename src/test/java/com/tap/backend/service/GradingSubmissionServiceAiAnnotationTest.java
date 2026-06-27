package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProperties;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.infra.storage.ObjectStorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

class GradingSubmissionServiceAiAnnotationTest {

    private GradingSubmissionService service;
    private AiProvider aiProvider;
    private ObjectStorageService storageService;
    private AiProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProvider = mock(AiProvider.class);
        storageService = mock(ObjectStorageService.class);
        aiProperties = new AiProperties("openai", null, null, null);

        service = new GradingSubmissionService(
                null, null, null, null, null, null, null,
                aiProvider, aiProperties, new ObjectMapper(),
                null, null, storageService, new AnnotatedStudentReportService(),
                null, null, null, null, null, null, null);
    }

    @Test
    void parsePageAnnotationJson_extractsValidEntries() {
        String response = """
                ```json
                [
                  {"anchor_text":"系统采用 MQTT 协议","note":"协议选型合理","type":"check","wavy":false},
                  {"anchor_text":"准确率达到了 95%","note":"结果优秀","type":"WAVE","wavy":true}
                ]
                ```
                """;

        List<AnnotatedStudentReportService.AnnotationEntry> entries = service.parsePageAnnotationJson(response);

        assertEquals(2, entries.size());
        assertEquals("CHECK", entries.get(0).type());
        assertTrue(entries.get(0).anchorText().contains("MQTT"));
        assertEquals("WAVE", entries.get(1).type());
        assertTrue(entries.get(1).wavy());
    }

    @Test
    void parsePageAnnotationJson_returnsEmptyForMalformedInput() {
        assertTrue(service.parsePageAnnotationJson(null).isEmpty());
        assertTrue(service.parsePageAnnotationJson("").isEmpty());
        assertTrue(service.parsePageAnnotationJson("no json here").isEmpty());
        assertTrue(service.parsePageAnnotationJson("{\"invalid\": \"object\"}").isEmpty());
    }

    @Test
    void generatePageLevelAiAnnotations_shortPdf_returnsAiAnnotations() throws Exception {
        byte[] pdfBytes = createTwoPagePdf();
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setPdfObjectKey("reports/student.pdf");

        when(storageService.getBytes("reports/student.pdf")).thenReturn(pdfBytes);
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.chat(anyString(), isNull())).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) {
                String prompt = invocation.getArgument(0);
                if (prompt.contains("第 1 / 2 页")) {
                    return "[{\"anchor_text\":\"Experiment Purpose\",\"note\":\"clear objective\",\"type\":\"CHECK\",\"wavy\":false}]";
                }
                return "[{\"anchor_text\":\"accuracy\",\"note\":\"excellent result\",\"type\":\"CHECK\",\"wavy\":false}]";
            }
        });

        List<AnnotatedStudentReportService.AnnotationEntry> annotations =
                service.generatePageLevelAiAnnotations(submission);

        assertEquals(2, annotations.size());
        assertTrue(annotations.stream().allMatch(a -> "CHECK".equals(a.type())));
        assertTrue(annotations.stream().anyMatch(a -> a.anchorText().contains("Purpose")));
        assertTrue(annotations.stream().anyMatch(a -> a.anchorText().contains("accuracy")));
    }

    @Test
    void generatePageLevelAiAnnotations_longPdf_readsFirstTenPages() throws Exception {
        byte[] pdfBytes = createMultiPagePdf(11);
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setPdfObjectKey("reports/long.pdf");

        when(storageService.getBytes("reports/long.pdf")).thenReturn(pdfBytes);
        when(aiProvider.name()).thenReturn("openai");
        when(aiProvider.chat(anyString(), isNull())).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) {
                String prompt = invocation.getArgument(0);
                if (prompt.contains("第 11 / 11 页")) {
                    return "[{\"anchor_text\":\"Page 11\",\"note\":\"should not be analyzed\",\"type\":\"CHECK\",\"wavy\":false}]";
                }
                return "[{\"anchor_text\":\"content\",\"note\":\"page checked\",\"type\":\"CHECK\",\"wavy\":false}]";
            }
        });

        List<AnnotatedStudentReportService.AnnotationEntry> annotations =
                service.generatePageLevelAiAnnotations(submission);

        assertEquals(10, annotations.size());
    }

    @Test
    void generatePageLevelAiAnnotations_mockProvider_returnsEmpty() throws Exception {
        byte[] pdfBytes = createTwoPagePdf();
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setPdfObjectKey("reports/mock.pdf");

        when(storageService.getBytes("reports/mock.pdf")).thenReturn(pdfBytes);
        when(aiProvider.name()).thenReturn("mock");

        List<AnnotatedStudentReportService.AnnotationEntry> annotations =
                service.generatePageLevelAiAnnotations(submission);

        assertTrue(annotations.isEmpty());
    }

    @Test
    void renderPdf_withExternalAnnotationsSkipsLegacyFallback() throws Exception {
        byte[] source;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 14);
                stream.newLineAtOffset(72, 700);
                stream.showText("Experiment result");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 11);
                stream.newLineAtOffset(72, 660);
                stream.showText("The system achieves 95% accuracy on the test set.");
                stream.endText();
            }
            document.save(output);
            source = output.toByteArray();
        }

        AnnotatedStudentReportService reportService = new AnnotatedStudentReportService();
        List<AnnotatedStudentReportService.AnnotationEntry> annotations = List.of(
                new AnnotatedStudentReportService.AnnotationEntry(
                        "ai-1", "CHECK", "准确率很高", "95% accuracy", false));

        AnnotatedStudentReportService.RenderedReport rendered = reportService.render(
                "sample.pdf",
                source,
                "测试学生",
                new java.math.BigDecimal("91"),
                "整体完成较好。",
                List.of(),
                "张老师",
                annotations);

        Path artifactsDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactsDir);
        Files.write(artifactsDir.resolve("ai-annotation-external.pdf"), rendered.bytes());

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            assertEquals(2, pdf.getNumberOfPages());
            BufferedImage image = new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 144);
            ImageIO.write(image, "png", artifactsDir.resolve("ai-annotation-external-page1.png").toFile());
        }
    }

    private byte[] createTwoPagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            addTextPage(document, "Experiment Purpose", "Understand the basic principles of neural networks and backpropagation.");
            addTextPage(document, "Experiment Result", "The model achieves 95% accuracy on the test set.");
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] createMultiPagePdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                addTextPage(document, "Page " + (i + 1), "This is the content of page " + (i + 1) + ".");
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private void addTextPage(PDDocument document, String heading, String body) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA_BOLD, 14);
            stream.newLineAtOffset(72, 700);
            stream.showText(heading);
            stream.endText();

            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 11);
            stream.newLineAtOffset(72, 660);
            stream.showText(body);
            stream.endText();
        }
    }
}
