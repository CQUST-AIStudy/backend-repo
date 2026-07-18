package com.tap.backend.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.dao.AiExperimentReportDao;
import com.tap.backend.academic.dao.StudentAiReportQueryDao;
import com.tap.backend.academic.entity.AiExperimentReport;
import com.tap.backend.academic.teacherexperiment.AiReportContext;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiReportServiceTest {
    @Mock StudentAiReportQueryDao queryDao;
    @Mock AiExperimentReportDao reportDao;
    @Mock AiReportGenerator generator;
    private AiReportService service;

    @BeforeEach
    void setUp() {
        service = new AiReportService(queryDao, reportDao, generator);
    }

    @Test
    void generateUsesUnifiedContextAndOrderedArtifactCode() throws Exception {
        AiReportContext context = context();
        Map<String, Object> userData = Map.of("studentName", "张三");
        when(queryDao.findContext("2026001", 7)).thenReturn(context);
        when(queryDao.findProblemRows("2026001", 7)).thenReturn(List.of(
                problem("反转链表", "int reverse() { return 1; }"),
                problem("空代码", "   "),
                problem("合并链表", "int merge() { return 2; }")));
        when(generator.generate(context,
                "第1题如下：\n// 反转链表\nint reverse() { return 1; }\n\n"
                        + "第2题如下：\n// 合并链表\nint merge() { return 2; }",
                userData))
                .thenReturn("# 报告\n## 实验目的\n学习链表");
        when(reportDao.upsert(7, 101, "# 报告\n## 实验目的\n学习链表")).thenReturn(1);

        AiReportResult result = service.generate("2026001", 7, userData);

        assertTrue(result.success());
        assertEquals("# 报告\n## 实验目的\n学习链表", result.report());
        assertEquals("张三", result.data().get("studentName"));
        assertEquals("2026001", result.data().get("studentId"));
        verify(reportDao).upsert(7, 101, "# 报告\n## 实验目的\n学习链表");
    }

    @Test
    void generateRejectsInaccessibleOffering() {
        when(queryDao.findContext("2026001", 7)).thenReturn(null);

        AiReportResult result = service.generate("2026001", 7, Map.of());

        assertFalse(result.success());
        assertEquals("实验不存在或无权访问", result.message());
        verify(queryDao, never()).findProblemRows("2026001", 7);
        verifyNoInteractions(reportDao, generator);
    }

    @Test
    void generateRejectsMissingCode() {
        when(queryDao.findContext("2026001", 7)).thenReturn(context());
        when(queryDao.findProblemRows("2026001", 7)).thenReturn(List.of(
                problem("空代码", " "), problem("无代码", null)));

        AiReportResult result = service.generate("2026001", 7, Map.of());

        assertFalse(result.success());
        assertEquals("该实验暂无代码提交，无法生成报告", result.message());
        verifyNoInteractions(reportDao, generator);
    }

    @Test
    void generateUsesCodeLoadedByExperimentListWhenUnifiedRowsAreEmpty() throws Exception {
        AiReportContext context = context();
        Map<String, Object> userData = Map.of("code", "int main() { return 0; }");
        when(queryDao.findContext("2026001", 7)).thenReturn(context);
        when(queryDao.findProblemRows("2026001", 7)).thenReturn(List.of());
        when(generator.generate(context, "int main() { return 0; }", userData))
                .thenReturn("generated report");
        when(reportDao.upsert(7, 101, "generated report")).thenReturn(1);

        AiReportResult result = service.generate("2026001", 7, userData);

        assertTrue(result.success());
        verify(reportDao).upsert(7, 101, "generated report");
    }

    @Test
    void getRejectsMissingReport() {
        when(queryDao.findContext("2026001", 7)).thenReturn(context());
        when(reportDao.findByOfferingAndStudent(7, 101)).thenReturn(null);

        AiReportResult result = service.get("2026001", 7);

        assertFalse(result.success());
        assertEquals("尚未生成报告", result.message());
    }

    @Test
    void getReturnsSavedReportForUnifiedIdentity() {
        AiExperimentReport saved = new AiExperimentReport();
        saved.setReportMd("saved report");
        when(queryDao.findContext("2026001", 7)).thenReturn(context());
        when(reportDao.findByOfferingAndStudent(7, 101)).thenReturn(saved);

        AiReportResult result = service.get("2026001", 7);

        assertTrue(result.success());
        assertEquals("saved report", result.report());
        assertEquals("2026001", result.data().get("studentId"));
        verify(reportDao).findByOfferingAndStudent(7, 101);
    }

    @Test
    void regenerationUpsertsReportUsingOfferingAndStudentProfile() throws Exception {
        AiReportContext context = context();
        when(queryDao.findContext("2026001", 7)).thenReturn(context);
        when(queryDao.findProblemRows("2026001", 7)).thenReturn(List.of(problem("链表", "code")));
        when(generator.generate(context, "第1题如下：\n// 链表\ncode", Map.of()))
                .thenReturn("regenerated report");
        when(reportDao.upsert(7, 101, "regenerated report")).thenReturn(2);

        AiReportResult result = service.generate("2026001", 7, null);

        assertTrue(result.success());
        verify(reportDao).upsert(7, 101, "regenerated report");
        verify(reportDao, never()).findByOfferingAndStudent(7, 101);
    }

    private AiReportContext context() {
        AiReportContext value = new AiReportContext();
        value.setStudentProfileId(101L);
        value.setStudentNo("2026001");
        value.setStudentName("张三");
        value.setOfferingId(7L);
        value.setName("链表实验");
        value.setDescription("完成链表操作");
        return value;
    }

    private TeacherSubmissionProblemRow problem(String title, String code) {
        TeacherSubmissionProblemRow value = new TeacherSubmissionProblemRow();
        value.setProblemTitle(title);
        value.setCode(code);
        return value;
    }
}
