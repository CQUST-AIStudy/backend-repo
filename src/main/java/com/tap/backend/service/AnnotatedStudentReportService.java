package com.tap.backend.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service that renders "red-pen teacher annotation" overlays onto student reports.
 * <p>
 * Supports both DOCX and PDF input files.  The output looks as if a teacher
 * physically marked the paper with a red pen: handwriting-style score on the
 * first page, scattered red check-marks (鈭? in the body, and a teacher review
 * block appended at the end.
 */
@Service
public class AnnotatedStudentReportService {
    public static final String FILE_TYPE_ANNOTATED_DOCX = "annodoc";
    public static final String FILE_TYPE_ANNOTATED_PDF = "annopdf";

    /* 鈹€鈹€ colour palette 鈹€鈹€ */
    private static final String RED_HEX = "D62828";
    private static final Color RED_COLOR = new Color(214, 40, 40);
    private static final Color RED_LIGHT = new Color(214, 40, 40, 180);

    /* 鈹€鈹€ font names 鈹€鈹€ */
    private static final String HANDWRITING_FONT = "\u534e\u6587\u884c\u6977";
    private static final String HANDWRITING_FALLBACK = "\u6977\u4f53";
    private static final String LATIN_FONT = "Times New Roman";
    private static final String CHECK_MARK_FONT = "Segoe UI Symbol";

    /* 鈹€鈹€ marks 鈹€鈹€ */
    private static final String DOCX_CHECK_MARK = "\u2713";
    private static final String PDF_CHECK_MARK = "V";
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};
    private static final List<String> SCORE_KEYWORDS = List.of(
            "\u5f97\u5206",
            "\u5206\u6570",
            "\u6210\u7ee9",
            "\u8bc4\u5206",
            "score",
            "\u603b\u5206"
    );
    private static final List<String> COVER_OR_METADATA_KEYWORDS = List.of(
            "\u91cd\u5e86\u79d1\u6280\u5927\u5b66",
            "\u5b9e\u9a8c\u62a5\u544a",
            "\u8bfe\u7a0b\u540d\u79f0",
            "\u5b66\u9662",
            "\u4e13\u4e1a\u73ed\u7ea7",
            "\u5b66\u751f\u59d3\u540d",
            "\u59d3\u540d",
            "\u5b66\u53f7",
            "\u6307\u5bfc\u6559\u5e08",
            "\u4efb\u8bfe\u6559\u5e08",
            "studentid",
            "studentno",
            "name",
            "class"
    );

    /* 鈹€鈹€ check-mark image cache (thread-safe lazy init) 鈹€鈹€ */
    private volatile byte[] checkMarkPngBytes;

    /**
     * Optional override for the CJK font used in annotated PDF reports.
     * Useful on servers where none of the built-in font candidates exist.
     */
    @Value("${tap.grading.report-font-path:}")
    private String configuredCjkFontPath;

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Public entry point
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    public RenderedReport render(String originalFilename,
                                 byte[] sourceBytes,
                                 String studentName,
                                 BigDecimal totalScore,
                                 String teacherComment,
                                 List<String> dimensionComments,
                                 String teacherSignature) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            throw new IllegalArgumentException("Source report is empty");
        }

        String normalizedFilename = originalFilename == null ? "" : originalFilename.toLowerCase(Locale.ROOT);
        try {
            if (normalizedFilename.endsWith(".docx")) {
                return renderDocx(sourceBytes, studentName, totalScore, teacherComment, dimensionComments, teacherSignature);
            }
            if (normalizedFilename.endsWith(".pdf") || isPdf(sourceBytes)) {
                return renderPdf(sourceBytes, studentName, totalScore, teacherComment, dimensionComments, teacherSignature);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to annotate report", e);
        }
        throw new IllegalArgumentException("Only PDF and DOCX student reports are supported");
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  DOCX rendering
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private RenderedReport renderDocx(byte[] sourceBytes,
                                      String studentName,
                                      BigDecimal totalScore,
                                      String teacherComment,
                                      List<String> dimensionComments,
                                      String teacherSignature) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(sourceBytes));
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Random random = buildRandom(studentName, totalScore);
            List<XWPFParagraph> paragraphs = collectDocxParagraphs(document);

            // 1) Red handwriting score on front page
            insertDocxScoreInFrontMatter(document, paragraphs, totalScore);

            // 2) Scattered red check-marks with handwriting-style images
            appendDocxCheckMarkImages(document, paragraphs, random);
            appendDocxProminentCheckMarks(document, paragraphs, random);

            // 3) Teacher review block at the end
            appendDocxReviewBlock(document, teacherComment, dimensionComments, teacherSignature);

            document.write(outputStream);
            return new RenderedReport(
                    FILE_TYPE_ANNOTATED_DOCX,
                    ".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    outputStream.toByteArray()
            );
        }
    }

    private List<XWPFParagraph> collectDocxParagraphs(XWPFDocument document) {
        List<XWPFParagraph> result = new ArrayList<>(document.getParagraphs());
        for (XWPFTable table : document.getTables()) {
            collectTableParagraphs(table, result);
        }
        return result;
    }

    private void collectTableParagraphs(XWPFTable table, List<XWPFParagraph> target) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                target.addAll(cell.getParagraphs());
                for (XWPFTable nested : cell.getTables()) {
                    collectTableParagraphs(nested, target);
                }
            }
        }
    }

    /**
     * Insert a red handwriting-style score near the top of the document,
     * next to an existing "寰楀垎" / "鎴愮哗" keyword if found.
     */
    private void insertDocxScoreInFrontMatter(XWPFDocument document,
                                              List<XWPFParagraph> paragraphs,
                                              BigDecimal totalScore) {
        String scoreText = " " + formatScore(totalScore) + "\u5206 ";
        int inspected = 0;
        for (XWPFParagraph paragraph : paragraphs) {
            String text = safeText(paragraph.getText());
            if (text.isBlank()) {
                continue;
            }
            inspected++;
            if (containsScoreKeyword(text)) {
                if (tryPlaceScoreIntoNeighborCell(paragraph, scoreText)) {
                    return;
                }
                appendDocxRun(paragraph, scoreText, 22, true);
                return;
            }
            if (inspected >= 24) {
                break;
            }
        }

        // Fallback: add to the first non-blank paragraph
        XWPFParagraph fallback = paragraphs.stream()
                .filter(paragraph -> !safeText(paragraph.getText()).isBlank())
                .findFirst()
                .orElseGet(document::createParagraph);
        appendDocxRun(fallback, "  " + scoreText.trim(), 22, true);
    }

    private boolean tryPlaceScoreIntoNeighborCell(XWPFParagraph paragraph, String scoreText) {
        IBody body = paragraph.getBody();
        if (!(body instanceof XWPFTableCell cell)) {
            return false;
        }
        XWPFTableRow row = cell.getTableRow();
        if (row == null) {
            return false;
        }
        List<XWPFTableCell> cells = row.getTableCells();
        int cellIndex = cells.indexOf(cell);
        if (cellIndex < 0 || cellIndex >= cells.size() - 1) {
            return false;
        }
        XWPFTableCell targetCell = cells.get(cellIndex + 1);
        targetCell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        XWPFParagraph targetParagraph = targetCell.getParagraphs().stream()
                .filter(p -> safeText(p.getText()).isBlank())
                .findFirst()
                .orElseGet(targetCell::addParagraph);
        targetParagraph.setAlignment(ParagraphAlignment.CENTER);
        appendDocxRun(targetParagraph, scoreText.trim(), 24, true);
        return true;
    }

    /**
     * Insert handwriting-style check-mark images (red 鈭? into random paragraphs.
     */
    private void appendDocxCheckMarkImages(XWPFDocument document,
                                           List<XWPFParagraph> paragraphs,
                                           Random random) {
        List<XWPFParagraph> candidates = paragraphs.stream()
                .filter(paragraph -> {
                    String text = safeText(paragraph.getText());
                    return !text.isBlank() && text.length() > 8;
                })
                .toList();
        int desired = Math.min(5, Math.max(2, candidates.size() / 10));
        byte[] checkImg = getCheckMarkPng();

        for (Integer index : pickIndices(candidates.size(), desired, random)) {
            XWPFParagraph paragraph = candidates.get(index);
            try {
                appendStandaloneDocxCheckMark(document, paragraph, checkImg, random);
            } catch (Exception ignored) {
                appendTextCheckMarkInline(paragraph, 72 + random.nextInt(10));
            }
        }
    }

    private void appendDocxProminentCheckMarks(XWPFDocument document,
                                               List<XWPFParagraph> paragraphs,
                                               Random random) {
        byte[] checkImg = getCheckMarkPng();
        XWPFParagraph firstAnchor = paragraphs.stream()
                .filter(paragraph -> !safeText(paragraph.getText()).isBlank())
                .findFirst()
                .orElseGet(document::createParagraph);
        try {
            appendStandaloneDocxCheckMark(document, firstAnchor, checkImg, random);
        } catch (Exception ignored) {
            XWPFParagraph fallback = insertDocxParagraphAfterAnchor(document, firstAnchor);
            fallback.setAlignment(ParagraphAlignment.RIGHT);
            appendTextCheckMarkInline(fallback, 84);
        }

        XWPFParagraph lastAnchor = null;
        for (int i = paragraphs.size() - 1; i >= 0; i--) {
            if (!safeText(paragraphs.get(i).getText()).isBlank()) {
                lastAnchor = paragraphs.get(i);
                break;
            }
        }
        if (lastAnchor == null) {
            lastAnchor = firstAnchor;
        }
        if (paragraphs.size() > 24 && lastAnchor != firstAnchor) {
            try {
                appendStandaloneDocxCheckMark(document, lastAnchor, checkImg, random);
            } catch (Exception ignored) {
                XWPFParagraph fallback = insertDocxParagraphAfterAnchor(document, lastAnchor);
                fallback.setAlignment(ParagraphAlignment.RIGHT);
                appendTextCheckMarkInline(fallback, 84);
            }
        }
    }

    private void appendStandaloneDocxCheckMark(XWPFDocument document,
                                               XWPFParagraph anchor,
                                               byte[] checkImg,
                                               Random random) throws Exception {
        XWPFParagraph markParagraph = insertDocxParagraphAfterAnchor(document, anchor);
        markParagraph.setAlignment(random.nextBoolean() ? ParagraphAlignment.RIGHT : ParagraphAlignment.CENTER);
        markParagraph.setSpacingBefore(0);
        markParagraph.setSpacingAfter(0);
        if (checkImg != null) {
            XWPFRun imgRun = markParagraph.createRun();
            imgRun.addPicture(
                    new ByteArrayInputStream(checkImg),
                    XWPFDocument.PICTURE_TYPE_PNG,
                    "check.png",
                    Units.toEMU(158 + random.nextInt(42)),
                    Units.toEMU(126 + random.nextInt(36))
            );
            XWPFRun spacer = markParagraph.createRun();
            spacer.setText(" ");
            XWPFRun textRun = markParagraph.createRun();
            styleDocxCheckRun(textRun, 74 + random.nextInt(10));
            textRun.setText(DOCX_CHECK_MARK);
        } else {
            appendTextCheckMarkInline(markParagraph, 74 + random.nextInt(12));
        }
    }

    private void appendTextCheckMarkInline(XWPFParagraph paragraph, int fontSize) {
        XWPFRun run = paragraph.createRun();
        styleDocxCheckRun(run, fontSize);
        run.setText(" " + DOCX_CHECK_MARK + " ");
    }

    /**
     * Append a teacher review block at the end of the document with a styled
     * separator and red handwriting-style text.
     */
    private void appendDocxReviewBlock(XWPFDocument document,
                                       String teacherComment,
                                       List<String> dimensionComments,
                                       String teacherSignature) {
        XWPFParagraph anchorParagraph = findDocxReviewAnchor(document);

        XWPFParagraph separator = insertDocxParagraphAfterAnchor(document, anchorParagraph);
        separator.setSpacingBefore(220);
        separator.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun sepRun = separator.createRun();
        styleDocxRun(sepRun, 11, false);
        sepRun.setText("\u002d\u002d\u002d\u002d\u002d\u002d\u002d\u002d \u6559\u5e08\u8bc4\u8bed \u002d\u002d\u002d\u002d\u002d\u002d\u002d\u002d");

        XWPFParagraph titleParagraph = insertDocxParagraphAfterAnchor(document, separator);
        titleParagraph.setAlignment(ParagraphAlignment.LEFT);
        titleParagraph.setSpacingBefore(100);
        titleParagraph.setSpacingAfter(30);
        appendDocxMixedText(titleParagraph, "\u6559\u5e08\u8bc4\u8bed\uff1a", 17, true);

        List<String> reviewLines = buildReviewLines(teacherComment, dimensionComments);
        XWPFParagraph lastParagraph = titleParagraph;
        for (String line : reviewLines) {
            XWPFParagraph paragraph = insertDocxParagraphAfterAnchor(document, lastParagraph);
            paragraph.setSpacingBefore(12);
            paragraph.setSpacingAfter(12);
            appendDocxMixedText(paragraph, line, 12, false);
            lastParagraph = paragraph;
        }

        XWPFParagraph sigPara = insertDocxParagraphAfterAnchor(document, lastParagraph);
        sigPara.setAlignment(ParagraphAlignment.RIGHT);
        sigPara.setSpacingBefore(140);
        appendDocxMixedText(sigPara, resolveTeacherSignature(teacherSignature), 14, true);
    }

    private void appendDocxRun(XWPFParagraph paragraph, String text, int fontSize, boolean bold) {
        XWPFRun run = paragraph.createRun();
        styleDocxRun(run, fontSize, bold);
        run.setText(text);
    }

    private void styleDocxRun(XWPFRun run, int fontSize, boolean bold) {
        run.setColor(RED_HEX);
        run.setBold(bold);
        run.setFontFamily(HANDWRITING_FONT);
        run.setFontSize(fontSize);

        CTRPr runProperties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = runProperties.addNewRFonts();
        fonts.setAscii(LATIN_FONT);
        fonts.setHAnsi(LATIN_FONT);
        fonts.setEastAsia(HANDWRITING_FONT);
    }

    private void styleDocxCheckRun(XWPFRun run, int fontSize) {
        run.setColor(RED_HEX);
        run.setBold(true);
        run.setFontFamily(CHECK_MARK_FONT);
        run.setFontSize(fontSize);

        CTRPr runProperties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = runProperties.addNewRFonts();
        fonts.setAscii(CHECK_MARK_FONT);
        fonts.setHAnsi(CHECK_MARK_FONT);
        fonts.setEastAsia(CHECK_MARK_FONT);
    }

    private void appendDocxMixedText(XWPFParagraph paragraph, String text, int fontSize, boolean bold) {
        String value = safeText(text);
        if (value.isBlank()) {
            return;
        }
        StringBuilder current = new StringBuilder();
        Boolean latinSegment = null;
        for (char ch : value.toCharArray()) {
            boolean latin = isLatinChar(ch);
            if (latinSegment == null || latinSegment != latin) {
                if (current.length() > 0) {
                    appendDocxTextSegment(paragraph, current.toString(), fontSize, bold, Boolean.TRUE.equals(latinSegment));
                    current.setLength(0);
                }
                latinSegment = latin;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            appendDocxTextSegment(paragraph, current.toString(), fontSize, bold, Boolean.TRUE.equals(latinSegment));
        }
    }

    private void appendDocxTextSegment(XWPFParagraph paragraph, String text, int fontSize, boolean bold, boolean latin) {
        XWPFRun run = paragraph.createRun();
        run.setColor(RED_HEX);
        run.setBold(bold);
        run.setFontFamily(latin ? LATIN_FONT : HANDWRITING_FONT);
        run.setFontSize(fontSize);

        CTRPr runProperties = run.getCTR().isSetRPr() ? run.getCTR().getRPr() : run.getCTR().addNewRPr();
        CTFonts fonts = runProperties.addNewRFonts();
        fonts.setAscii(LATIN_FONT);
        fonts.setHAnsi(LATIN_FONT);
        fonts.setEastAsia(HANDWRITING_FONT);
        run.setText(text);
    }

    private XWPFParagraph findDocxReviewAnchor(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = paragraphs.size() - 1; i >= 0; i--) {
            XWPFParagraph paragraph = paragraphs.get(i);
            String text = safeText(paragraph.getText()).replace(" ", "");
            if (text.matches(".*[-_—一~]{6,}.*")) {
                return paragraph;
            }
        }
        return paragraphs.isEmpty() ? document.createParagraph() : paragraphs.get(paragraphs.size() - 1);
    }

    private XWPFParagraph insertDocxParagraphAfterAnchor(XWPFDocument document, XWPFParagraph anchor) {
        if (anchor == null) {
            return document.createParagraph();
        }
        XmlCursor cursor = anchor.getCTP().newCursor();
        try {
            cursor.toEndToken();
            XWPFParagraph paragraph = document.insertNewParagraph(cursor);
            return paragraph != null ? paragraph : document.createParagraph();
        } catch (Exception ignored) {
            return document.createParagraph();
        } finally {
            cursor.dispose();
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  PDF rendering
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private RenderedReport renderPdf(byte[] sourceBytes,
                                     String studentName,
                                     BigDecimal totalScore,
                                     String teacherComment,
                                     List<String> dimensionComments,
                                     String teacherSignature) throws IOException {
        try (PDDocument document = PDDocument.load(sourceBytes);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Random random = buildRandom(studentName, totalScore);
            FontSelection fontSelection = loadPdfFont(document);

            if (document.getNumberOfPages() == 0) {
                document.addPage(new PDPage(PDRectangle.A4));
            }

            List<PDPage> pages = new ArrayList<>();
            document.getPages().forEach(pages::add);

            // 1) Draw score on first page
            drawPdfScoreOnFirstPage(document, pages.get(0), fontSelection, totalScore);

            // 2) Draw content-aware marks: checks on solid paragraphs, crosses +
            //    margin annotations on weak dimensions (anchored to real text lines)
            drawPdfContentMarks(document, pages, fontSelection, dimensionComments, random);

            // 3) Draw review on last page
            drawPdfReviewOnLastPage(document, pages.get(pages.size() - 1), fontSelection,
                    teacherComment, dimensionComments, teacherSignature);

            document.save(outputStream);
            return new RenderedReport(FILE_TYPE_ANNOTATED_PDF, ".pdf", "application/pdf", outputStream.toByteArray());
        }
    }

    private void drawPdfScoreOnFirstPage(PDDocument document,
                                         PDPage page,
                                         FontSelection fontSelection,
                                         BigDecimal totalScore) throws IOException {
        String scoreLabel = normalizeForFont(fontSelection,
                formatScore(totalScore) + "\u5206",
                "Score: " + formatScore(totalScore));
        PdfTextAnchor anchor = locatePdfKeyword(document, 1, SCORE_KEYWORDS);
        PDRectangle box = page.getMediaBox();
        float textWidth = measurePdfTextWidth(fontSelection, scoreLabel, 20f);

        float x, y;
        if (anchor != null) {
            float centerX = Math.min(box.getWidth() - 72f, Math.max(box.getWidth() * 0.78f, anchor.endX() + 92f));
            x = Math.max(box.getWidth() * 0.62f, centerX - textWidth / 2f);
            y = Math.max(48f, box.getHeight() - anchor.yDirAdj() - 2f);
        } else {
            x = box.getWidth() * 0.78f - textWidth / 2f;
            y = box.getHeight() - 52f;
        }

        try (PDPageContentStream stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            stream.setNonStrokingColor(RED_COLOR);
            drawPdfText(stream, fontSelection, 20f, x, y, scoreLabel);

            // Draw a subtle underline
            stream.setStrokingColor(RED_LIGHT);
            stream.setLineWidth(1.2f);
            stream.moveTo(x, y - 3f);
            stream.lineTo(x + textWidth, y - 3f);
            stream.stroke();
        }
    }

    /**
     * Draw teacher marks anchored to real text lines instead of random page
     * positions, so they never sit on top of figures or body text:
     * <ul>
     *   <li>check marks (red) at the end of solid paragraph lines;</li>
     *   <li>cross marks + wavy underline + short margin note on lines linked to
     *       weak dimensions (one per "建议" comment);</li>
     *   <li>short praise notes next to one or two check marks ("优点" comments).</li>
     * </ul>
     */
    private void drawPdfContentMarks(PDDocument document,
                                     List<PDPage> pages,
                                     FontSelection fontSelection,
                                     List<String> dimensionComments,
                                     Random random) throws IOException {
        List<TextLine> allCandidates = collectPdfLines(document).stream()
                .filter(line -> line.text().length() >= 8)
                .filter(line -> line.baselineY() > 72f)
                .filter(line -> line.pageIndex() >= 0 && line.pageIndex() < pages.size())
                .filter(line -> isLikelyAnnotationTarget(line, pages.size()))
                .toList();

        List<TextLine> bodyCandidates = allCandidates.stream()
                .filter(line -> pages.size() <= 1 || line.pageIndex() > 0)
                .toList();
        List<TextLine> candidates = bodyCandidates.isEmpty() ? allCandidates : bodyCandidates;
        if (candidates.isEmpty()) {
            return;
        }

        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();
        for (String comment : dimensionComments == null ? List.<String>of() : dimensionComments) {
            String value = safeText(comment).trim();
            if (value.startsWith("\u4f18\u70b9")) {
                strengths.add(stripAnnotationPrefix(value));
            } else if (value.startsWith("\u5efa\u8bae")) {
                weaknesses.add(stripAnnotationPrefix(value));
            }
        }

        int crossCount = Math.min(weaknesses.size(), 2);
        int checkCount = Math.max(3, Math.min(7, candidates.size() / 12));
        List<Integer> anchors = pickSpreadIndices(candidates.size(), checkCount + crossCount, random);
        if (anchors.isEmpty()) {
            return;
        }

        // Crosses go to anchors in the latter half of the document so the
        // "needs improvement" notes appear after the work has been presented.
        Set<Integer> crossAnchors = new HashSet<>();
        for (int i = anchors.size() - 1; i >= 0 && crossAnchors.size() < crossCount; i--) {
            crossAnchors.add(anchors.get(i));
        }

        Map<Integer, List<Float>> placedYPerPage = new HashMap<>();
        int strengthNoteBudget = Math.min(strengths.size(), 2);
        int weaknessIdx = 0;
        int strengthIdx = 0;

        for (Integer anchorIdx : anchors) {
            TextLine line = candidates.get(anchorIdx);
            PDPage page = pages.get(line.pageIndex());
            PDRectangle box = visibleBox(page);

            // Skip if too close to another mark on the same page
            List<Float> placed = placedYPerPage.computeIfAbsent(line.pageIndex(), k -> new ArrayList<>());
            boolean tooClose = placed.stream().anyMatch(y -> Math.abs(y - line.baselineY()) < 56f);
            if (tooClose) {
                continue;
            }
            placed.add(line.baselineY());

            boolean isCross = crossAnchors.contains(anchorIdx);
            float size = isCross ? 24f + random.nextInt(7) : 36f + random.nextInt(10);
            float markX = Math.min(line.endX() + 14f + size * 0.5f, box.getUpperRightX() - size * 0.7f - 8f);
            float markY = line.baselineY() + size * 0.12f;
            float angle = (float) Math.toRadians(-10 + random.nextInt(18));

            try (PDPageContentStream stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                String note = null;
                if (isCross) {
                    drawPdfWavyUnderline(stream, line.startX(), line.endX(), line.baselineY() - 3f);
                    drawPdfCrossStroke(stream, markX, markY, size, angle);
                    if (weaknessIdx < weaknesses.size()) {
                        note = weaknesses.get(weaknessIdx++);
                    }
                } else {
                    drawPdfCheckStroke(stream, markX, markY, size, angle);
                    if (strengthIdx < strengthNoteBudget) {
                        note = strengths.get(strengthIdx++);
                    }
                }
                if (note != null && !note.isBlank()) {
                    drawPdfMarginNote(stream, fontSelection, box, line, markX + size * 0.8f, note);
                }
            }
        }
    }

    /** Strip the "优点：" / "建议：" prefix and trailing punctuation for short margin notes. */
    private String stripAnnotationPrefix(String comment) {
        String value = comment;
        int colon = value.indexOf('\uff1a');
        if (colon < 0) {
            colon = value.indexOf(':');
        }
        if (colon >= 0 && colon < 4) {
            value = value.substring(colon + 1);
        }
        value = value.replace("\u3002", " ").trim();
        // Keep margin notes compact enough for the page edge, but preserve a
        // concrete reason instead of reducing the note to a vague label.
        for (char sep : new char[]{'\uff0c', ',', ';', '\uff1b'}) {
            int idx = value.indexOf(sep);
            if (idx > 20) {
                value = value.substring(0, idx);
                break;
            }
        }
        return value.length() > 50 ? value.substring(0, 50) : value;
    }

    private boolean isLikelyAnnotationTarget(TextLine line, int pageCount) {
        String compact = safeText(line.text()).replaceAll("\\s+", "");
        if (compact.length() < 12) {
            return false;
        }
        String lower = compact.toLowerCase(Locale.ROOT);
        for (String keyword : COVER_OR_METADATA_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        long letters = compact.codePoints().filter(Character::isLetter).count();
        long digits = compact.codePoints().filter(Character::isDigit).count();
        if (digits > 0 && digits >= compact.length() * 0.45d) {
            return false;
        }
        if (letters < Math.min(8, compact.length() / 2)) {
            return false;
        }
        return pageCount <= 1 || line.fontSize() < 28f;
    }

    /**
     * Draw a short red note near the mark.  Prefers the blank space right of the
     * line end; falls back to the gap just above the line when too narrow.
     */
    private void drawPdfMarginNote(PDPageContentStream stream,
                                   FontSelection fontSelection,
                                   PDRectangle box,
                                   TextLine line,
                                   float preferredX,
                                   String note) throws IOException {
        if (!fontSelection.supportsChinese() && note.codePoints().anyMatch(cp -> cp > 0x024F)) {
            return;
        }
        float fontSize = 10.5f;
        float width = measurePdfTextWidth(fontSelection, note, fontSize);
        float rightLimit = box.getUpperRightX() - 16f;
        stream.setNonStrokingColor(RED_COLOR);
        if (preferredX + 6f + width <= rightLimit) {
            drawPdfText(stream, fontSelection, fontSize, preferredX + 6f, line.baselineY(), note);
            return;
        }
        // Fall back: right-aligned in the gap above the line
        float x = Math.max(box.getLowerLeftX() + 16f, rightLimit - width);
        drawPdfText(stream, fontSelection, fontSize, x, line.baselineY() + Math.max(line.fontSize(), 9f) + 4f, note);
    }

    private void drawPdfCrossStroke(PDPageContentStream stream,
                                    float x,
                                    float y,
                                    float size,
                                    float angle) throws IOException {
        stream.setStrokingColor(RED_COLOR);
        stream.setLineWidth(Math.max(2.2f, size / 8f));
        stream.setLineCapStyle(1);
        stream.setLineJoinStyle(1);

        float[] a1 = rotatePoint(x, y, -size * 0.42f,  size * 0.46f, angle);
        float[] a2 = rotatePoint(x, y,  size * 0.46f, -size * 0.42f, angle);
        float[] b1 = rotatePoint(x, y, -size * 0.44f, -size * 0.40f, angle);
        float[] b2 = rotatePoint(x, y,  size * 0.42f,  size * 0.48f, angle);
        float[] ac = rotatePoint(x, y,  size * 0.04f,  size * 0.06f, angle);
        float[] bc = rotatePoint(x, y, -size * 0.02f,  size * 0.02f, angle);

        stream.moveTo(a1[0], a1[1]);
        stream.curveTo(ac[0], ac[1], ac[0], ac[1], a2[0], a2[1]);
        stream.stroke();
        stream.moveTo(b1[0], b1[1]);
        stream.curveTo(bc[0], bc[1], bc[0], bc[1], b2[0], b2[1]);
        stream.stroke();
    }

    /** Wavy red underline used to flag a weak passage, like a teacher's squiggle. */
    private void drawPdfWavyUnderline(PDPageContentStream stream,
                                      float startX,
                                      float endX,
                                      float y) throws IOException {
        if (endX - startX < 24f) {
            return;
        }
        stream.setStrokingColor(RED_LIGHT);
        stream.setLineWidth(1.1f);
        float waveLen = 9f;
        float amplitude = 2.2f;
        float x = startX;
        stream.moveTo(x, y);
        boolean up = true;
        while (x + waveLen <= endX) {
            float midX = x + waveLen / 2f;
            float ctrlY = up ? y + amplitude : y - amplitude;
            stream.curveTo(midX, ctrlY, midX, ctrlY, x + waveLen, y);
            x += waveLen;
            up = !up;
        }
        stream.stroke();
    }

    /** Evenly spread {@code count} indices over {@code size} elements with jitter. */
    private List<Integer> pickSpreadIndices(int size, int count, Random random) {
        if (size <= 0 || count <= 0) {
            return List.of();
        }
        count = Math.min(count, size);
        Set<Integer> used = new HashSet<>();
        List<Integer> out = new ArrayList<>();
        float step = (float) size / count;
        for (int i = 0; i < count; i++) {
            int base = (int) (i * step + step * 0.2f + random.nextFloat() * step * 0.6f);
            int idx = Math.min(size - 1, Math.max(0, base));
            while (used.contains(idx) && idx < size - 1) {
                idx++;
            }
            if (used.add(idx)) {
                out.add(idx);
            }
        }
        Collections.sort(out);
        return out;
    }

    private List<TextLine> collectPdfLines(PDDocument document) throws IOException {
        PdfLineCollector collector = new PdfLineCollector();
        collector.getText(document);
        return collector.lines();
    }

    private void drawPdfCheckStroke(PDPageContentStream stream,
                                    float x,
                                    float y,
                                    float size,
                                    float angle) throws IOException {
        stream.setStrokingColor(RED_COLOR);
        stream.setLineWidth(Math.max(3.4f, size / 8f));
        stream.setLineCapStyle(1);
        stream.setLineJoinStyle(1);

        float[] p1 = rotatePoint(x, y, -size * 0.46f,  size * 0.10f, angle);
        float[] p2 = rotatePoint(x, y, -size * 0.22f, -size * 0.22f, angle);
        float[] p3 = rotatePoint(x, y,  size * 0.54f,  size * 0.56f, angle);
        float[] c1 = rotatePoint(x, y, -size * 0.38f,  size * 0.16f, angle);
        float[] c2 = rotatePoint(x, y, -size * 0.30f, -size * 0.04f, angle);
        float[] c3 = rotatePoint(x, y, -size * 0.02f, -size * 0.02f, angle);
        float[] c4 = rotatePoint(x, y,  size * 0.22f,  size * 0.22f, angle);

        stream.moveTo(p1[0], p1[1]);
        stream.curveTo(c1[0], c1[1], c2[0], c2[1], p2[0], p2[1]);
        stream.curveTo(c3[0], c3[1], c4[0], c4[1], p3[0], p3[1]);
        stream.stroke();
    }

    private float[] rotatePoint(float originX,
                                float originY,
                                float localX,
                                float localY,
                                float angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        return new float[]{
                originX + localX * cos - localY * sin,
                originY + localX * sin + localY * cos
        };
    }

        private void drawPdfReviewOnLastPage(PDDocument document,
                                         PDPage page,
                                         FontSelection fontSelection,
                                         String teacherComment,
                                         List<String> dimensionComments,
                                         String teacherSignature) throws IOException {
        List<StyledLine> styledLines = new ArrayList<>();
        styledLines.add(new StyledLine(normalizeForFont(fontSelection,
                "-------- 教师评语 --------", "-------- Teacher Review --------"), 12f));
        styledLines.add(new StyledLine(normalizeForFont(fontSelection,
                "教师评语", "Teacher Review"), 16f));
        for (String line : buildReviewLines(teacherComment, dimensionComments)) {
            styledLines.add(new StyledLine(normalizeForFont(fontSelection, line, line), 11f));
        }
        String signatureLine = normalizeForFont(fontSelection, resolveTeacherSignature(teacherSignature), "Teacher");

        PDRectangle templateBox = page.getMediaBox();
        float margin = 44f;
        float maxWidth = templateBox.getWidth() - margin * 2;
        PDPage currentPage = page;
        float initialStartY = findPdfReviewStartY(document, page, templateBox);
        // Not enough blank space left on the last page: start the review block on
        // a fresh page instead of overlaying it on existing body text.
        if (initialStartY < 170f) {
            currentPage = new PDPage(templateBox);
            document.addPage(currentPage);
            initialStartY = templateBox.getHeight() - 72f;
        }
        float y;

        PDPageContentStream stream = new PDPageContentStream(document, currentPage, AppendMode.APPEND, true, true);
        try {
            stream.setNonStrokingColor(RED_COLOR);
            y = startPdfReviewSection(stream, templateBox, margin, fontSelection, false, initialStartY);
            for (StyledLine styledLine : styledLines) {
                List<String> wrapped = wrapPdfText(
                        fontSelection,
                        styledLine.text(),
                        styledLine.fontSize(),
                        maxWidth
                );
                for (String line : wrapped) {
                    float nextLineHeight = styledLine.fontSize() + 6f;
                    if (y - nextLineHeight < 40f) {
                        stream.close();
                        currentPage = new PDPage(templateBox);
                        document.addPage(currentPage);
                        stream = new PDPageContentStream(document, currentPage, AppendMode.APPEND, true, true);
                        stream.setNonStrokingColor(RED_COLOR);
                        y = startPdfReviewSection(stream, templateBox, margin, fontSelection, true, templateBox.getHeight() - 72f);
                    }
                    drawPdfText(stream, fontSelection, styledLine.fontSize(), margin, y, line);
                    y -= nextLineHeight;
                }
                y -= 4f;
            }
            if (y - 20f < 40f) {
                stream.close();
                currentPage = new PDPage(templateBox);
                document.addPage(currentPage);
                stream = new PDPageContentStream(document, currentPage, AppendMode.APPEND, true, true);
                stream.setNonStrokingColor(RED_COLOR);
                y = startPdfReviewSection(stream, templateBox, margin, fontSelection, true, templateBox.getHeight() - 72f);
            }
            float sigWidth = measurePdfTextWidth(fontSelection, signatureLine, 12f);
            drawPdfText(stream, fontSelection, 12f, templateBox.getWidth() - margin - sigWidth, y - 8f, signatureLine);
        } finally {
            stream.close();
        }
    }

    private float startPdfReviewSection(PDPageContentStream stream,
                                        PDRectangle box,
                                        float margin,
                                        FontSelection fontSelection,
                                        boolean continued,
                                        float startY) throws IOException {
        float y = startY;
        stream.setStrokingColor(RED_LIGHT);
        stream.setLineWidth(0.9f);
        stream.moveTo(margin, y + 10f);
        stream.lineTo(box.getWidth() - margin, y + 10f);
        stream.stroke();
        y -= continued ? 10f : 4f;
        return y;
    }

    /**
     * Returns the highest Y at which the review block may safely start on the
     * given page (i.e. just below the lowest existing text).  No lower clamp is
     * applied here: callers must check the value and switch to a new page when
     * the remaining space is insufficient, otherwise the review would be drawn
     * on top of existing content.
     */
    private float findPdfReviewStartY(PDDocument document, PDPage page, PDRectangle box) throws IOException {
        PdfPageMetrics metrics = locatePdfPageMetrics(document, page);
        float candidate = metrics.lowestTextY() > 0f ? metrics.lowestTextY() - 34f : box.getHeight() - 72f;
        float maxY = box.getHeight() - 72f;
        return Math.min(maxY, candidate);
    }

    private PDRectangle visibleBox(PDPage page) {
        PDRectangle crop = page.getCropBox();
        return crop != null ? crop : page.getMediaBox();
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Check-mark image generation (for DOCX)
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    /**
     * Generate a red handwriting-style check-mark as a PNG image.
     * This is cached because every annotation needs the same base image.
     */
    private byte[] getCheckMarkPng() {
        if (checkMarkPngBytes != null) {
            return checkMarkPngBytes;
        }
        synchronized (this) {
            if (checkMarkPngBytes != null) {
                return checkMarkPngBytes;
            }
            try {
                checkMarkPngBytes = renderCheckMarkImage();
            } catch (Exception e) {
                return null;
            }
        }
        return checkMarkPngBytes;
    }

        private byte[] renderCheckMarkImage() throws IOException {
        int w = 220, h = 180;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(RED_COLOR);
        g.setStroke(new BasicStroke(14.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Path2D.Float path = new Path2D.Float();
        path.moveTo(20, 100);
        path.curveTo(38, 92, 56, 110, 74, 138);
        path.curveTo(98, 106, 128, 62, 188, 18);

        g.draw(path);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  PDF text utilities
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private PdfTextAnchor locatePdfKeyword(PDDocument document, int pageNumber, List<String> keywords) throws IOException {
        PdfKeywordLocator locator = new PdfKeywordLocator(pageNumber, keywords);
        locator.getText(document);
        return locator.anchor();
    }

    private PdfPageMetrics locatePdfPageMetrics(PDDocument document, PDPage page) throws IOException {
        int pageNumber = 1;
        int index = 0;
        for (PDPage candidate : document.getPages()) {
            index++;
            // Compare underlying COS dictionaries: PDPageTree may hand out a new
            // PDPage wrapper per iteration, so reference equality on PDPage fails.
            if (candidate.getCOSObject() == page.getCOSObject()) {
                pageNumber = index;
                break;
            }
        }
        PdfPageMetricsLocator locator = new PdfPageMetricsLocator(pageNumber, page.getMediaBox());
        locator.getText(document);
        return locator.metrics();
    }

    private void drawPdfText(PDPageContentStream stream, FontSelection fontSelection, float fontSize, float x, float y, String text)
            throws IOException {
        String value = safeText(text);
        if (value.isBlank()) {
            return;
        }
        float cursorX = x;
        StringBuilder current = new StringBuilder();
        PDFont currentFont = null;
        for (char ch : value.toCharArray()) {
            PDFont nextFont = resolvePdfFont(fontSelection, ch);
            if (currentFont == null || currentFont != nextFont) {
                cursorX = flushPdfSegment(stream, currentFont, fontSize, cursorX, y, current.toString());
                current.setLength(0);
                currentFont = nextFont;
            }
            current.append(ch);
        }
        flushPdfSegment(stream, currentFont, fontSize, cursorX, y, current.toString());
    }

    private float flushPdfSegment(PDPageContentStream stream,
                                  PDFont font,
                                  float fontSize,
                                  float x,
                                  float y,
                                  String text) throws IOException {
        String sanitized = sanitizeForPdfFont(font, text);
        if (font == null || sanitized.isBlank()) {
            return x;
        }
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(x, y);
        stream.showText(sanitized);
        stream.endText();
        return x + font.getStringWidth(sanitized) / 1000f * fontSize;
    }

    private List<String> wrapPdfText(FontSelection fontSelection, String text, float fontSize, float maxWidth) throws IOException {
        if (safeText(text).isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : safeText(text).split("\n")) {
            if (rawLine.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (char ch : rawLine.toCharArray()) {
                String next = current + String.valueOf(ch);
                float width = measurePdfTextWidth(fontSelection, next, fontSize);
                if (width > maxWidth && current.length() > 0) {
                    lines.add(current.toString());
                    current = new StringBuilder().append(ch);
                } else {
                    current.append(ch);
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
        }
        return lines;
    }

    private FontSelection loadPdfFont(PDDocument document) throws IOException {
        List<Path> chineseCandidates = new ArrayList<>();
        if (configuredCjkFontPath != null && !configuredCjkFontPath.isBlank()) {
            chineseCandidates.add(Path.of(configuredCjkFontPath.trim()));
        }
        chineseCandidates.addAll(List.of(
                Path.of("C:\\Windows\\Fonts\\STXINGKA.TTF"),
                Path.of("C:\\Windows\\Fonts\\simkai.ttf"),
                Path.of("C:\\Windows\\Fonts\\STKAITI.TTF"),
                Path.of("C:\\Windows\\Fonts\\KAIU.TTF"),
                Path.of("C:\\Windows\\Fonts\\simfang.ttf"),
                Path.of("C:\\Windows\\Fonts\\simhei.ttf"),
                Path.of("C:\\Windows\\Fonts\\Deng.ttf"),
                Path.of("C:\\Windows\\Fonts\\msyh.ttf"),
                Path.of("C:\\Windows\\Fonts\\msyh.ttc"),
                Path.of("C:\\Windows\\Fonts\\simsun.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
                Path.of("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/opentype/noto/NotoSerifCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/truetype/arphic/ukai.ttc"),
                Path.of("/usr/share/fonts/truetype/arphic/uming.ttc"),
                Path.of("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf")
        ));
        List<Path> latinCandidates = List.of(
                Path.of("C:\\Windows\\Fonts\\times.ttf"),
                Path.of("C:\\Windows\\Fonts\\timesbd.ttf"),
                Path.of("C:\\Windows\\Fonts\\arial.ttf"),
                Path.of("C:\\Windows\\Fonts\\calibri.ttf")
        );
        PDFont chineseFont = tryLoadPdfFont(document, chineseCandidates);
        PDFont latinFont = tryLoadPdfFont(document, latinCandidates);
        if (chineseFont != null) {
            return new FontSelection(chineseFont, latinFont != null ? latinFont : chineseFont, true);
        }
        PDFont fallback = latinFont != null ? latinFont : PDType1Font.HELVETICA_BOLD;
        return new FontSelection(fallback, fallback, false);
    }

    private PDFont tryLoadPdfFont(PDDocument document, List<Path> candidates) throws IOException {
        for (Path path : candidates) {
            if (!Files.exists(path)) {
                continue;
            }
            try {
                if (path.toString().toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                    PDFont font = loadFromTrueTypeCollection(document, path);
                    if (font != null) {
                        return font;
                    }
                    continue;
                }
                try (var inputStream = Files.newInputStream(path)) {
                    return PDType0Font.load(document, inputStream, true);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * Loads the first font of a TrueType Collection (.ttc).
     * The collection is constructed from an InputStream so the font data lives in
     * memory and stays valid until the PDF (with the subset font) is saved.
     */
    private PDFont loadFromTrueTypeCollection(PDDocument document, Path path) {
        try (var inputStream = Files.newInputStream(path)) {
            TrueTypeCollection collection = new TrueTypeCollection(inputStream);
            TrueTypeFont[] first = new TrueTypeFont[1];
            collection.processAllFonts(font -> {
                if (first[0] == null) {
                    first[0] = font;
                }
            });
            if (first[0] != null) {
                return PDType0Font.load(document, first[0], true);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String normalizeForFont(FontSelection fontSelection, String preferred, String fallback) {
        return fontSelection.supportsChinese() ? safeText(preferred) : safeText(fallback);
    }

    private String sanitizeForPdfFont(PDFont font, String text) {
        String value = safeText(text);
        if (value.isBlank()) {
            return value;
        }
        try {
            font.encode(value);
            return value;
        } catch (Exception ignored) {
        }

        StringBuilder sanitized = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                sanitized.append(ch);
                continue;
            }
            try {
                font.encode(String.valueOf(ch));
                sanitized.append(ch);
            } catch (Exception ignored) {
                sanitized.append(' ');
            }
        }
        return sanitized.toString().replaceAll(" {2,}", " ").trim();
    }

    private float measurePdfTextWidth(FontSelection fontSelection, String text, float fontSize) throws IOException {
        float width = 0f;
        String value = safeText(text);
        if (value.isBlank()) {
            return width;
        }
        StringBuilder current = new StringBuilder();
        PDFont currentFont = null;
        for (char ch : value.toCharArray()) {
            PDFont nextFont = resolvePdfFont(fontSelection, ch);
            if (currentFont == null || currentFont != nextFont) {
                width += measurePdfSegment(currentFont, current.toString(), fontSize);
                current.setLength(0);
                currentFont = nextFont;
            }
            current.append(ch);
        }
        width += measurePdfSegment(currentFont, current.toString(), fontSize);
        return width;
    }

    private float measurePdfSegment(PDFont font, String text, float fontSize) throws IOException {
        String sanitized = sanitizeForPdfFont(font, text);
        if (font == null || sanitized.isBlank()) {
            return 0f;
        }
        return font.getStringWidth(sanitized) / 1000f * fontSize;
    }

    private PDFont resolvePdfFont(FontSelection fontSelection, char ch) {
        PDFont latin = fontSelection.latinFont() == null ? fontSelection.font() : fontSelection.latinFont();
        if (isLatinChar(ch) && canEncodePdfChar(latin, ch)) {
            return latin;
        }
        if (canEncodePdfChar(fontSelection.font(), ch)) {
            return fontSelection.font();
        }
        if (canEncodePdfChar(latin, ch)) {
            return latin;
        }
        return fontSelection.font();
    }

    private boolean canEncodePdfChar(PDFont font, char ch) {
        if (font == null) {
            return false;
        }
        try {
            font.encode(String.valueOf(ch));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Shared utilities
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private boolean containsScoreKeyword(String text) {
        String lower = safeText(text).toLowerCase(Locale.ROOT);
        for (String keyword : SCORE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

        private List<String> buildReviewLines(String teacherComment, List<String> dimensionComments) {
        List<String> lines = new ArrayList<>();
        if (teacherComment != null && !teacherComment.isBlank()) {
            for (String line : teacherComment.replace("\r", "").split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    lines.add(trimmed);
                }
            }
        }
        List<String> detailLines = new ArrayList<>();
        for (String comment : dimensionComments == null ? List.<String>of() : dimensionComments) {
            String trimmed = safeText(comment).trim();
            if (!trimmed.isBlank() && !detailLines.contains(trimmed)) {
                detailLines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            lines.add("批阅完成，请继续围绕实验任务、原理理解、结果分析与总结反思进一步完善报告。");
        }
        if (!detailLines.isEmpty()) {
            lines.add("");
            lines.add("分项批注：");
            lines.addAll(detailLines);
        }
        return lines.size() > 44 ? lines.subList(0, 44) : lines;
    }

    private String resolveTeacherSignature(String teacherSignature) {
        String normalized = safeText(teacherSignature).trim();
        return normalized.isBlank() ? "任课教师" : normalized;
    }

    private List<Integer> pickIndices(int size, int desiredCount, Random random) {
        if (size <= 0 || desiredCount <= 0) {
            return List.of();
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices, random);
        return indices.stream()
                .limit(Math.min(size, desiredCount))
                .sorted()
                .toList();
    }

    private Random buildRandom(String studentName, BigDecimal totalScore) {
        return new Random(Objects.hash(studentName, formatScore(totalScore)));
    }

    private boolean isPdf(byte[] sourceBytes) {
        if (sourceBytes.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (sourceBytes[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private String formatScore(BigDecimal totalScore) {
        if (totalScore == null) {
            return "待评";
        }
        return totalScore.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private boolean isLatinChar(char ch) {
        return ch <= 0x024F && !Character.UnicodeScript.HAN.equals(Character.UnicodeScript.of(ch));
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Records & inner classes
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    public record RenderedReport(String fileType, String extension, String contentType, byte[] bytes) {}

    private record FontSelection(PDFont font, PDFont latinFont, boolean supportsChinese) {}

    private record StyledLine(String text, float fontSize) {}

    private record PdfTextAnchor(float endX, float yDirAdj) {}

    private record PdfPageMetrics(float lowestTextY) {}

    /** A fragment of text on a page, in PDF coordinates (origin bottom-left). */
    private record TextLine(int pageIndex, String text, float startX, float endX, float baselineY, float fontSize) {}

    /** Collects every text line with its position so marks can anchor to real content. */
    private static final class PdfLineCollector extends PDFTextStripper {
        private final List<TextLine> lines = new ArrayList<>();

        private PdfLineCollector() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text == null || text.isBlank() || positions == null || positions.isEmpty()) {
                return;
            }
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            float pageHeight = getCurrentPage().getMediaBox().getHeight();
            lines.add(new TextLine(
                    getCurrentPageNo() - 1,
                    text.trim(),
                    first.getXDirAdj(),
                    last.getXDirAdj() + last.getWidthDirAdj(),
                    pageHeight - last.getYDirAdj(),
                    last.getFontSizeInPt()
            ));
        }

        private List<TextLine> lines() {
            return lines;
        }
    }

    private static final class PdfKeywordLocator extends PDFTextStripper {
        private final List<String> keywords;
        private PdfTextAnchor anchor;

        private PdfKeywordLocator(int pageNumber, List<String> keywords) throws IOException {
            this.keywords = keywords;
            setStartPage(pageNumber);
            setEndPage(pageNumber);
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (anchor != null || text == null || positions == null || positions.isEmpty()) {
                return;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                int start = lower.indexOf(keyword.toLowerCase(Locale.ROOT));
                if (start < 0 || start >= positions.size()) {
                    continue;
                }
                int end = Math.min(positions.size() - 1, start + keyword.length() - 1);
                TextPosition endPos = positions.get(end);
                anchor = new PdfTextAnchor(endPos.getXDirAdj() + endPos.getWidthDirAdj(), endPos.getYDirAdj());
                return;
            }
        }

        private PdfTextAnchor anchor() {
            return anchor;
        }
    }

    private static final class PdfPageMetricsLocator extends PDFTextStripper {
        private final PDRectangle box;
        private float lowestTextY = -1f;

        private PdfPageMetricsLocator(int pageNumber, PDRectangle box) throws IOException {
            this.box = box;
            setStartPage(pageNumber);
            setEndPage(pageNumber);
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty() || text == null || text.isBlank()) {
                return;
            }
            for (TextPosition position : positions) {
                float pageY = box.getHeight() - position.getYDirAdj();
                if (lowestTextY < 0f || pageY < lowestTextY) {
                    lowestTextY = pageY;
                }
            }
        }

        private PdfPageMetrics metrics() {
            return new PdfPageMetrics(lowestTextY);
        }
    }
}

