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
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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
            document.addPage(new PDPage(PDRectangle.A4));
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
            assertEquals(2, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("91") || text.toLowerCase().contains("score"));
            assertTrue(text.contains("张老师") || text.toLowerCase().contains("teacher"));
            assertEquals(1, countOccurrences(text, "教师评语"));
            assertFalse(text.contains("分项批注"));
        }
    }

    @Test
    void renderPdfAddsVisibleCheckMarkNearRightMiddle() throws Exception {
        byte[] source;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
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
                stream.showText("The report explains model training results and compares several output charts with reasonable observations.");
                stream.endText();
            }
            document.save(output);
            source = output.toByteArray();
        }

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "sample.pdf",
                source,
                "测试学生",
                new BigDecimal("91"),
                "整体完成较好，但结果分析还可以继续深化。",
                List.of("优点：结果截图齐全", "建议：分析部分可以再展开"),
                "张老师"
        );

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 144);
            int redPixels = countRedPixels(
                    image,
                    0.60, 0.97,
                    0.10, 0.90
            );
            assertTrue(redPixels > 150,
                    "Expected visible red annotation pixels, but found only " + redPixels);
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

    @Test
    void renderProvidedExperimentPdfKeepsReviewSeparateAndScoreVisible() throws Exception {
        Path sample = Path.of("D:\\Downloads\\2023440415邹名格人工智能实验2 (1).pdf");
        if (!Files.exists(sample)) {
            return;
        }
        byte[] source = Files.readAllBytes(sample);
        int originalPages;
        try (PDDocument pdf = PDDocument.load(source)) {
            originalPages = pdf.getNumberOfPages();
        }

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                sample.getFileName().toString(),
                source,
                "邹名格",
                new BigDecimal("50"),
                "你的实验报告完成了数据读取、规范化处理以及图像特征提取等基本流程，能够体现出对实验任务的理解。\n\n后续建议把关键步骤为什么这样做、结果为什么能说明问题写得更充分，尤其是围绕代码输出、图像结果和实验结论之间的对应关系展开说明。",
                List.of("建议：结果分析需要更具体", "建议：代码说明可以补充关键参数含义"),
                "张老师",
                List.of(
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-1", "WAVE", "这里需要补充为什么该结果能支持结论", "边缘特征主要反映图像中灰度变化较大的区域", true),
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-2", "CROSS", "对异常或边界情况的说明还不够", "边缘特征在目标识别、图像分割和图像分析中具有重要作用", false)
                )
        );

        Path artifactsDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactsDir);
        Files.write(artifactsDir.resolve("zou-annotated-positioning.pdf"), rendered.bytes());

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            assertEquals(originalPages + 1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("50"));
            assertTrue(text.contains("张老师"));
            assertEquals(1, countOccurrences(text, "教师评语"));
            assertFalse(text.contains("分项批注"));

            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int i = 0; i < Math.min(4, pdf.getNumberOfPages()); i++) {
                ImageIO.write(renderer.renderImageWithDPI(i, 144), "png",
                        artifactsDir.resolve("zou-annotated-page-" + (i + 1) + ".png").toFile());
            }
            ImageIO.write(renderer.renderImageWithDPI(pdf.getNumberOfPages() - 1, 144), "png",
                    artifactsDir.resolve("zou-annotated-review-page.png").toFile());
        }
    }

    @Test
    void renderPdfKeepsMultipleAnnotationsOnSameAnchorWhenNotesDiffer() throws Exception {
        byte[] source = createSimpleErrorReportPdf(
                "Experiment result",
                "The environment is Python 3.10 + OpenCV 4.8 and it supports this edge detection task."
        );

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "same-anchor.pdf",
                source,
                "测试学生",
                new BigDecimal("85"),
                "整体完成较好。",
                List.of(),
                "张老师",
                List.of(
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-1", "CHECK", "clear environment version", "Python 3.10 + OpenCV 4.8", false),
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-2", "WAVE", "explain why these libraries were chosen", "Python 3.10 + OpenCV 4.8", true)
                )
        );

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            System.out.println("=== PDF PAGE COUNT: " + pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            System.out.println("=== EXTRACTED PDF TEXT (length=" + text.length() + ") ===");
            // Print each character's codepoint for non-printable chars
            StringBuilder hex = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c >= 32 && c < 127) hex.append(c);
                else hex.append("\\u").append(String.format("%04x", (int) c));
            }
            System.out.println("HEX: " + hex.toString());
            System.out.println("=== END EXTRACTED PDF TEXT ===");
            assertTrue(text.contains("clear environment version"), "Expected 'clear environment version' in text but got:\n" + text);
            assertTrue(text.contains("explain why these libraries were chosen"), "Expected 'explain why these libraries were chosen' in text but got:\n" + text);
        }
    }

    @Test
    void renderPdfFindsAnchorWithSmallTextDifferences() throws Exception {
        byte[] source = createSimpleErrorReportPdf(
                "Experiment result",
                "The student writes that Sobel is a second order derivative operator, which is inaccurate."
        );

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "fuzzy-anchor.pdf",
                source,
                "测试学生",
                new BigDecimal("72"),
                "原理说明需要修正。",
                List.of(),
                "张老师",
                List.of(new AnnotatedStudentReportService.AnnotationEntry(
                        "ev-sobel",
                        "WAVE",
                        "Sobel 不是二阶导数算子，需要修正原理描述",
                        "second-order derivative operator",
                        true
                ))
        );

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Sobel"));
            assertTrue(text.contains("Sobel 不是二阶导数算子"));

            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(0, 144);
            int redPixels = countRedPixels(image, 0.10, 0.95, 0.10, 0.90);
            assertTrue(redPixels > 120, "Expected visible fuzzy-anchored red annotation pixels, found " + redPixels);
        }
    }

    @Test
    void renderPdfDoesNotAppendSecondReviewWhenSourceAlreadyHasInlineReview() throws Exception {
        byte[] source = createPdfWithInlineGeneratedReview();

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "already-reviewed.pdf",
                source,
                "测试学生",
                new BigDecimal("82"),
                "This new teacher review should not be appended again.",
                List.of("分项批注不应被追加到最终评语页"),
                "Teacher Wang",
                List.of()
        );

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertEquals(1, countOccurrences(text, "Teacher Review"));
            assertFalse(text.contains("This new teacher review should not be appended again."));
            assertFalse(text.contains("Teacher Wang"));
            assertFalse(text.contains("分项批注"));
        }
    }

    @Test
    void generateErrorReportFixtureForManualAcceptance() throws Exception {
        byte[] source = createSimpleErrorReportPdf(
                "Experiment result",
                List.of(
                        "The environment is Python 3.10 + OpenCV 4.8 and it supports this edge detection task.",
                        "The student writes that Sobel is a second-order derivative operator, which is inaccurate.",
                        "The image path is hard-coded as C:/data/image.jpg, so the experiment is not reproducible.",
                        "The conclusion says the accuracy reaches 100 percent without showing validation evidence."
                )
        );

        Path artifactsDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactsDir);
        Path sourcePdf = artifactsDir.resolve("annotation-positioning-error-report.pdf");
        Files.write(sourcePdf, source);

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "annotation-positioning-error-report.pdf",
                source,
                "验收学生",
                new BigDecimal("72"),
                "报告能够覆盖基本实验步骤，但对关键原理和结果证据的说明还不够充分。建议补充算子原理、路径配置说明和验证数据，让结论能被复现和核查。",
                List.of("Sobel 算子原理表述不准确", "实验路径硬编码影响复现", "准确率结论缺少验证证据"),
                "张老师",
                List.of(
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-ok", "CHECK", "环境版本写得比较清楚", "Python 3.10 + OpenCV 4.8", false),
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-sobel", "WAVE", "Sobel 不是二阶导数算子，需要修正原理描述", "second-order derivative operator", true),
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-path", "CROSS", "硬编码本地路径会影响他人复现实验", "C:/data/image.jpg", false),
                        new AnnotatedStudentReportService.AnnotationEntry(
                                "ev-accuracy", "WAVE", "100 percent accuracy 需要给出验证集或测试依据", "accuracy reaches 100 percent", true)
                )
        );

        Path annotatedPdf = artifactsDir.resolve("annotation-positioning-error-report-annotated.pdf");
        Files.write(annotatedPdf, rendered.bytes());

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Sobel"));
            assertTrue(text.contains("硬编码本地路径"));
            assertTrue(text.contains("100 percent accuracy"));
            PDFRenderer renderer = new PDFRenderer(pdf);
            ImageIO.write(renderer.renderImageWithDPI(0, 144), "png",
                    artifactsDir.resolve("annotation-positioning-error-report-annotated-page-1.png").toFile());
        }
    }

    @Test
    void renderRealPointerReportDerivesPreciseErrorAnnotationsWhenWorkerOmitsThem() throws Exception {
        Path sourcePdf = Path.of("src", "test", "resources", "reports",
                "pointer-array-report-with-errors.pdf");
        byte[] source = Files.readAllBytes(sourcePdf);

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "pointer-array-report-with-errors.pdf",
                source,
                "邹名格",
                new BigDecimal("51"),
                "报告完成了基本实验流程，但循环边界和临时指针的使用存在明显错误，需要结合异常输出重新检查代码。",
                List.of(
                        "代码正确性这部分需要重点修正：循环条件'i <= n'会越界访问，swap函数中的'temp'未初始化就被解引用。",
                        "数组遍历也使用了'i <= 5'，因此错误读取了arr[5]。",
                        "报告把[程序异常终止]解释为成功交换，结论与运行结果矛盾。"
                ),
                "张老师",
                List.of()
        );

        Path artifactsDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactsDir);
        Path annotatedPdf = artifactsDir.resolve("pointer-array-report-with-errors-annotated.pdf");
        Files.write(annotatedPdf, rendered.bytes());

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            assertEquals(7, pdf.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(pdf);
            int markedErrorPages = 0;
            for (int pageIndex : List.of(2, 4, 5)) {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 144);
                ImageIO.write(image, "png", artifactsDir.resolve(
                        "pointer-array-report-annotated-page-" + (pageIndex + 1) + ".png").toFile());
                if (countRedPixels(image, 0.08, 0.96, 0.08, 0.94) > 250) {
                    markedErrorPages++;
                }
            }
            assertTrue(markedErrorPages >= 2,
                    "Expected visible error marks on at least two real report pages, found " + markedErrorPages);
            BufferedImage cover = renderer.renderImageWithDPI(0, 144);
            assertTrue(countRedPixels(cover, 0.08, 0.96, 0.08, 0.94) < 1800,
                    "The cover page must not receive a large random correction mark");
        }
    }

    @Test
    void renderLongDocumentPdfSimulatesCheckmarksOnMainPages() throws Exception {
        Path sample = Path.of("D:\\Downloads\\谢晓娟-2025521044-实训报告.pdf");
        if (!Files.exists(sample)) {
            return;
        }
        byte[] source = Files.readAllBytes(sample);
        int originalPages;
        try (PDDocument pdf = PDDocument.load(source)) {
            originalPages = pdf.getNumberOfPages();
        }
        if (originalPages <= 10) {
            return;
        }

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                sample.getFileName().toString(),
                source,
                "谢晓娟",
                new BigDecimal("78"),
                "报告整体完成较好，实验步骤记录完整，建议在结果分析部分进一步结合代码输出说明结论依据。",
                List.of("实验步骤记录完整", "结果分析可进一步深化"),
                "张老师",
                List.of(),
                List.of(
                        new AnnotatedStudentReportService.DimensionScore("目标1", new BigDecimal("16")),
                        new AnnotatedStudentReportService.DimensionScore("目标2", new BigDecimal("10"))
                )
        );

        Path artifactsDir = Path.of("target", "test-artifacts");
        Files.createDirectories(artifactsDir);
        String runId = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMddHHmmss"));
        String baseName = "xie-annotated-" + runId;
        Files.write(artifactsDir.resolve(baseName + ".pdf"), rendered.bytes());

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            assertEquals(originalPages + 1, pdf.getNumberOfPages());

            // Diagnostic: print first page text to understand table layout.
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String firstPageText = stripper.getText(pdf);
            System.out.println("=== FIRST PAGE TEXT ===");
            System.out.println(firstPageText);
            System.out.println("=== END FIRST PAGE TEXT ===");

            // This template is a 60-point course-objective table: the displayed
            // total must be the sum of the visible objective rows, not the
            // platform's normalized 100-point total.
            assertTrue(firstPageText.contains("16分"), "Expected 目标1 score on cover page");
            assertTrue(firstPageText.contains("31分"), "Expected 目标2 score to be rescaled to the 40-point row");
            assertTrue(firstPageText.contains("47分"), "Expected objective-table total to equal visible row scores on a 60-point table");
            assertFalse(firstPageText.contains("10分"), "Course-objective row score must not keep an obviously inconsistent AI fallback");
            assertFalse(firstPageText.contains("78分"), "Course-objective table total must not use normalized task total directly");

            PDFRenderer renderer = new PDFRenderer(pdf);
            PDFTextStripper pageStripper = new PDFTextStripper();

            int markedMainPages = 0;
            int checkedMainPages = 0;
            int referencePages = 0;
            List<Double> markCenters = new java.util.ArrayList<>();
            for (int i = 10; i < originalPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 144);
                ImageIO.write(image, "png", artifactsDir.resolve(baseName + "-page-" + (i + 1) + ".png").toFile());

                pageStripper.setStartPage(i + 1);
                pageStripper.setEndPage(i + 1);
                String pageText = pageStripper.getText(pdf).replaceAll("\\s+", "");
                boolean isReferencePage = pageText.contains("参考文献")
                        || pageText.contains("参考资料")
                        || pageText.matches(".*\\[[0-9]+\\].*");

                if (isReferencePage) {
                    referencePages++;
                    continue;
                }

                checkedMainPages++;
                if (countRedPixels(image, 0.08, 0.96, 0.08, 0.94) > 250) {
                    markedMainPages++;
                    markCenters.add(redPixelCenterXRatio(image));
                }
            }

            // Also save the first three pages for manual inspection.
            for (int i = 0; i < Math.min(3, originalPages); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 144);
                ImageIO.write(image, "png", artifactsDir.resolve(baseName + "-page-" + (i + 1) + ".png").toFile());
            }
            ImageIO.write(renderer.renderImageWithDPI(pdf.getNumberOfPages() - 1, 144), "png",
                    artifactsDir.resolve(baseName + "-review-page.png").toFile());

            assertTrue(checkedMainPages > 0,
                    "Expected at least one main content page to check");
            assertEquals(checkedMainPages, markedMainPages,
                    "Expected every main content page (excluding references) to have a visible check mark, but only "
                            + markedMainPages + "/" + checkedMainPages + " were marked (skipped " + referencePages + " reference pages)");
            assertTrue(markCenters.stream().anyMatch(x -> x < 0.82),
                    "Expected at least one simulated check mark to be near the text body instead of all marks staying in the far-right margin: "
                            + markCenters);
        }
    }

    @Test
    void longDocumentSimulationSkipsCoverTableOfContentsAndReferences() {
        List<String> pageTexts = List.of(
                "重庆科技大学 实验报告 课程名称 人工智能",
                "目录 一、实验目的 二、实验过程 三、实验结果",
                "摘要 本文介绍实验背景和主要内容",
                "一、实验目的 掌握数据预处理流程",
                "二、实验过程 这里是正文分析和代码说明",
                "参考文献 [1] Python 文档 [2] OpenCV 文档 [3] PyTorch 文档"
        );

        assertEquals(List.of(3, 4), service.selectLongDocumentSimulatedPageIndexes(pageTexts));
    }

    @Test
    void longDocumentSimulationCanStartAfterFirstTenPages() {
        List<String> pageTexts = new java.util.ArrayList<>();
        pageTexts.add("重庆科技大学 实验报告");
        for (int i = 2; i <= 12; i++) {
            pageTexts.add("第" + i + "页 正文内容 方法步骤 结果分析");
        }
        pageTexts.add("参考文献 [1] 文献 [2] 文献 [3] 文献");

        assertEquals(List.of(10, 11), service.selectLongDocumentSimulatedPageIndexes(pageTexts, 10));
    }

    @Test
    void renderPdfRemovesTrailingGeneratedReviewBeforeAddingNewOne() throws Exception {
        byte[] source = createPdfWithTrailingGeneratedReviewPage();

        AnnotatedStudentReportService.RenderedReport rendered = service.render(
                "old-annotated.pdf",
                source,
                "测试学生",
                new BigDecimal("78"),
                "This is the only current teacher review.",
                List.of(),
                "Teacher Zhang",
                List.of()
        );

        try (PDDocument pdf = PDDocument.load(rendered.bytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertEquals(1, countOccurrences(text, "This is the only current teacher review."));
            assertEquals(1, countOccurrences(text, "Teacher Zhang"));
            assertFalse(text.contains("Old generated review should be removed."));
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

    private byte[] createSimpleErrorReportPdf(String heading, String bodyLine) throws Exception {
        return createSimpleErrorReportPdf(heading, List.of(bodyLine));
    }

    private byte[] createSimpleErrorReportPdf(String heading, List<String> bodyLines) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 14);
                stream.newLineAtOffset(72, 720);
                stream.showText("Chongqing University of Science and Technology");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 13);
                stream.newLineAtOffset(72, 660);
                stream.showText(heading);
                stream.endText();

                float y = 625;
                for (String bodyLine : bodyLines) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 11);
                    stream.newLineAtOffset(72, y);
                    stream.showText(bodyLine);
                    stream.endText();
                    y -= 26;
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] createPdfWithTrailingGeneratedReviewPage() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage reportPage = new PDPage(PDRectangle.A4);
            document.addPage(reportPage);
            try (PDPageContentStream stream = new PDPageContentStream(document, reportPage)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 14);
                stream.newLineAtOffset(72, 720);
                stream.showText("Experiment result");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 11);
                stream.newLineAtOffset(72, 680);
                stream.showText("The experiment implements a basic image preprocessing workflow.");
                stream.endText();
            }

            PDPage oldReviewPage = new PDPage();
            document.addPage(oldReviewPage);
            try (PDPageContentStream stream = new PDPageContentStream(document, oldReviewPage)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                stream.newLineAtOffset(72, 720);
                stream.showText("Teacher Review");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 11);
                stream.newLineAtOffset(72, 680);
                stream.showText("Old generated review should be removed.");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(430, 620);
                stream.showText("Teacher Zhang");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] createPdfWithInlineGeneratedReview() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 14);
                stream.newLineAtOffset(72, 720);
                stream.showText("Experiment result");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 11);
                stream.newLineAtOffset(72, 680);
                stream.showText("The experiment implements a basic image preprocessing workflow.");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA_BOLD, 16);
                stream.newLineAtOffset(72, 260);
                stream.showText("Teacher Review");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 11);
                stream.newLineAtOffset(72, 228);
                stream.showText("Existing inline review should be kept without duplication.");
                stream.endText();

                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(430, 190);
                stream.showText("Teacher Zhang");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
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

    private double redPixelCenterXRatio(BufferedImage image) {
        long weightedX = 0;
        int redPixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 170 && g < 120 && b < 120) {
                    weightedX += x;
                    redPixels++;
                }
            }
        }
        if (redPixels == 0) {
            return 1.0d;
        }
        return (weightedX / (double) redPixels) / image.getWidth();
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
