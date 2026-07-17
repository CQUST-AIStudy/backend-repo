package com.tap.backend.academic.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tap.backend.academic.dao.SubmissionDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Submission;
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
    @Mock SubmissionDao submissionDao;
    @Mock ExperimentService experimentService;
    @Mock AiReportGenerator generator;
    @Mock TeacherExperimentQueryDao teacherExperimentQueryDao;
    private AiReportService service;

    @BeforeEach
    void setUp() {
        service = new AiReportService(submissionDao, experimentService, generator, teacherExperimentQueryDao);
    }

    @Test
    void generateRejectsMissingExperiment() {
        AiReportResult result = service.generate("2026001", 7, Map.of());
        assertFalse(result.success());
        assertEquals("实验不存在", result.message());
        verifyNoInteractions(submissionDao, generator);
    }

    @Test
    void generateRejectsSubmissionWithoutCode() {
        when(experimentService.findExperimentById(7)).thenReturn(experiment(7));
        when(submissionDao.findByUsernameAndExperimentId("2026001", 7))
                .thenReturn(submission(11, " ", null));
        AiReportResult result = service.generate("2026001", 7, Map.of());
        assertFalse(result.success());
        assertEquals("该实验暂无代码提交，无法生成报告", result.message());
        verifyNoInteractions(generator);
    }

    @Test
    void generatePersistsAndReturnsReport() throws Exception {
        Experiment experiment = experiment(7);
        Submission submission = submission(11, "int main(){}", null);
        when(experimentService.findExperimentById(7)).thenReturn(experiment);
        when(submissionDao.findByUsernameAndExperimentId("2026001", 7)).thenReturn(submission);
        when(generator.generate(experiment, submission, Map.of("studentName", "张三")))
                .thenReturn("# 报告\n## 实验目的\n学习链表");
        when(submissionDao.updateReport(11, "# 报告\n## 实验目的\n学习链表")).thenReturn(1);

        AiReportResult result = service.generate("2026001", 7, Map.of("studentName", "张三"));

        assertTrue(result.success());
        assertEquals("# 报告\n## 实验目的\n学习链表", result.report());
        assertEquals("张三", result.data().get("studentName"));
        assertEquals("2026001", result.data().get("studentId"));
    }

    @Test
    void getReturnsOnlyRequestedStudentsSavedReport() {
        when(experimentService.findExperimentById(7)).thenReturn(experiment(7));
        when(submissionDao.findByUsernameAndExperimentId("2026001", 7))
                .thenReturn(submission(11, "code", "saved report"));

        AiReportResult result = service.get("2026001", 7);

        assertTrue(result.success());
        assertEquals("saved report", result.report());
        verify(submissionDao).findByUsernameAndExperimentId("2026001", 7);
        verifyNoMoreInteractions(submissionDao);
    }

    @Test
    void generateUsesUnifiedArtifactCodeWhenLegacySubmissionIsMissing() throws Exception {
        Experiment experiment = experiment(7);
        TeacherSubmissionProblemRow problem = new TeacherSubmissionProblemRow();
        problem.setProblemTitle("反转链表");
        problem.setCode("int main(){return 0;}");
        when(experimentService.findExperimentById(7)).thenReturn(experiment);
        when(teacherExperimentQueryDao.findSubmissionProblemRows("2026001", 7)).thenReturn(List.of(problem));
        when(generator.generate(eq(experiment), any(Submission.class), eq(Map.of("studentName", "张三"))))
                .thenReturn("# 报告\n## 实验目的\n学习链表");
        when(submissionDao.saveSubmission(any(Submission.class))).thenReturn(1);

        AiReportResult result = service.generate("2026001", 7, Map.of("studentName", "张三"));

        assertTrue(result.success());
        verify(generator).generate(eq(experiment), argThat(s -> s.getCode().contains("int main")), anyMap());
        verify(submissionDao).saveSubmission(argThat(s -> "# 报告\n## 实验目的\n学习链表".equals(s.getReport())));
    }

    private Experiment experiment(int id) {
        Experiment value = new Experiment();
        value.setExperiment_id(id);
        value.setName("链表实验");
        return value;
    }

    private Submission submission(int id, String code, String report) {
        Submission value = new Submission();
        value.setSubmission_id(id);
        value.setUsername("2026001");
        value.setExperiment_id(7);
        value.setCode(code);
        value.setReport(report);
        return value;
    }
}
