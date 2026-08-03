package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * 批注版报告布局回归：封面没有「指导教师/签字」栏时，教师签名应跟在
 * 「教师评语」正文之后（同一页），而不是单独成页与评语分离。
 */
class AnnotatedStudentReportServiceTest {

    @Test
    void teacherSignatureFollowsReviewOnSamePage() throws Exception {
        byte[] source;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(60, 780);
                stream.showText("Experiment Report Body");
                stream.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            source = bos.toByteArray();
        }

        AnnotatedStudentReportService service = new AnnotatedStudentReportService();
        AnnotatedStudentReportService.RenderedReport report = service.render(
                "report.pdf", source, "张三", new BigDecimal("85"),
                "这次实验完成得不错，思路清楚，继续保持。", List.of(), "邹老师");

        try (PDDocument out = PDDocument.load(report.bytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            boolean reviewPageFound = false;
            for (int i = 1; i <= out.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(out);
                if (text.contains("教师评语")) {
                    reviewPageFound = true;
                    assertTrue(text.contains("邹老师"),
                            "教师签名应与教师评语同页（评语之后），但第 " + i + " 页只有评语没有签名");
                }
            }
            assertTrue(reviewPageFound, "应存在教师评语页");
        }
    }
}
