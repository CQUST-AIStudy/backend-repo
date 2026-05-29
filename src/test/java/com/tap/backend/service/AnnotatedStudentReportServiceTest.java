package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class AnnotatedStudentReportServiceTest {

    private final AnnotatedStudentReportService service = new AnnotatedStudentReportService();

    @Test
    void renderDocxAddsTeacherAnnotations() throws Exception {
        Path sample = Path.of("..", "2025520535-杨天-实验1.docx");
        if (!Files.exists(sample)) {
            return;
        }
        byte[] source = Files.readAllBytes(sample);

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                sample.getFileName().toString(),
                source,
                "杨天",
                new BigDecimal("86"),
                "本次实验整体完成较好，建议进一步加强结果分析的针对性。",
                List.of("图表说明较完整", "结论还可以再凝练一些"),
                "张老师"
        );

        assertEquals(AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_DOCX, rendered.fileType());
        assertFalse(rendered.bytes().length == 0);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(rendered.bytes()))) {
            String text = new XWPFWordExtractor(document).getText();
            assertTrue(text.contains("86"));
            assertTrue(text.contains("教师评语"));
            assertTrue(text.contains("张老师"));
        }
    }

    @Test
    void renderPdfAddsScoreAndReview() throws Exception {
        byte[] source;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            source = output.toByteArray();
        }

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "sample.pdf",
                source,
                "测试学生",
                new BigDecimal("91"),
                "整体完成度较高，建议继续把关键实验步骤和结论依据写得更扎实。",
                List.of("结果截图齐全", "分析部分可以再展开"),
                "张老师"
        );

        assertEquals(AnnotatedStudentReportService.FILE_TYPE_ANNOTATED_PDF, rendered.fileType());
        assertTrue(rendered.bytes().length > source.length);

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            assertEquals(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("91") || text.toLowerCase().contains("score"));
            assertTrue(text.contains("张老师") || text.toLowerCase().contains("teacher"));
        }
    }

    @Test
    void renderPdfAddsVisibleCheckMarkNearRightMiddle() throws Exception {
        byte[] source;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            source = output.toByteArray();
        }

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "sample.pdf",
                source,
                "测试学生",
                new BigDecimal("91"),
                "整体完成较好，但结果分析还可以继续深化。",
                List.of(),
                "张老师"
        );

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 144);
            int redPixels = countRedPixels(
                    image,
                    0.80, 0.97,
                    0.38, 0.62
            );
            assertTrue(redPixels > 250,
                    "Expected visible red annotation pixels in the right-middle area, but found only " + redPixels);
        }
    }

    @Test
    void renderRealPdfAddsVisibleCheckMarkNearRightMiddle() throws Exception {
        Path sample = findRealPdfSample();
        if (sample == null) {
            return;
        }
        byte[] source = Files.readAllBytes(sample);

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                sample.getFileName().toString(),
                source,
                "胡嘉瑞",
                new BigDecimal("84"),
                "整体完成较好，但结果分析还可以继续深化。",
                List.of(),
                "张老师"
        );

        Path artifactsDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactsDir);
        Files.write(artifactsDir.resolve("real-annotated-check.pdf"), rendered.bytes());

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 144);
            int redPixels = countRedPixels(
                    image,
                    0.80, 0.97,
                    0.38, 0.62
            );
            assertTrue(redPixels > 250,
                    "Expected visible red annotation pixels in the right-middle area of the real PDF, but found only " + redPixels);
        }
    }

    private Path findRealPdfSample() throws Exception {
        List<Path> roots = List.of(
                Path.of("G:\\myapps"),
                Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent()
        );
        for (Path root : roots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (var paths = Files.list(root)) {
                Path sample = paths
                        .filter(path -> path.getFileName().toString().endsWith(".pdf"))
                        .filter(path -> path.getFileName().toString().contains("胡嘉瑞"))
                        .findFirst()
                        .orElse(null);
                if (sample != null) {
                    return sample;
                }
            }
        }
        return null;
    }

    private int countRedPixels(BufferedImage image,
                               double xStartRatio,
                               double xEndRatio,
                               double yStartRatio,
                               double yEndRatio) {
        int width = image.getWidth();
        int height = image.getHeight();
        int startX = Math.max(0, (int) (width * xStartRatio));
        int endX = Math.min(width, (int) (width * xEndRatio));
        int startY = Math.max(0, (int) (height * yStartRatio));
        int endY = Math.min(height, (int) (height * yEndRatio));

        int redPixels = 0;
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 170 && g < 120 && b < 120) {
                    redPixels++;
                }
            }
        }
        return redPixels;
    }
}
