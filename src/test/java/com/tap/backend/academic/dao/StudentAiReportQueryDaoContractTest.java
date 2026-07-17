package com.tap.backend.academic.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tap.backend.academic.teacherexperiment.AiReportContext;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

class StudentAiReportQueryDaoContractTest {

    @Test
    void exposesUnifiedContextAndProblemQueries() throws Exception {
        Method context = StudentAiReportQueryDao.class.getMethod("findContext", String.class, long.class);
        assertEquals(AiReportContext.class, context.getReturnType());
        assertEquals("studentNo", context.getParameters()[0].getAnnotation(Param.class).value());
        assertEquals("offeringId", context.getParameters()[1].getAnnotation(Param.class).value());

        Method problems = StudentAiReportQueryDao.class.getMethod("findProblemRows", String.class, long.class);
        assertEquals(List.class, problems.getReturnType());
        assertEquals("studentNo", problems.getParameters()[0].getAnnotation(Param.class).value());
        assertEquals("offeringId", problems.getParameters()[1].getAnnotation(Param.class).value());
        assertEquals(
                TeacherSubmissionProblemRow.class,
                ((java.lang.reflect.ParameterizedType) problems.getGenericReturnType())
                        .getActualTypeArguments()[0]);
    }

    @Test
    void contextCarriesUnifiedReportInputs() throws Exception {
        assertNotNull(AiReportContext.class.getMethod("getStudentProfileId"));
        assertNotNull(AiReportContext.class.getMethod("getStudentNo"));
        assertNotNull(AiReportContext.class.getMethod("getStudentName"));
        assertNotNull(AiReportContext.class.getMethod("getOfferingId"));
        assertNotNull(AiReportContext.class.getMethod("getName"));
        assertNotNull(AiReportContext.class.getMethod("getDescription"));
        assertNotNull(AiReportContext.class.getMethod("getStatus"));
        assertNotNull(AiReportContext.class.getMethod("getScore"));
        assertNotNull(AiReportContext.class.getMethod("getSubmitTime"));
    }

    @Test
    void mapsOnlyUnifiedReportSources() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mappers/StudentAiReportQueryMapper.xml")) {
            assertNotNull(stream, "StudentAiReportQueryMapper.xml should be on the classpath");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(xml.contains("id=\"findContext\""));
            assertTrue(xml.contains("student_profile"));
            assertTrue(xml.contains("class_student"));
            assertTrue(xml.contains("teaching_class"));
            assertTrue(xml.contains("assignment_offering"));
            assertTrue(xml.contains("assignment_template"));
            assertTrue(xml.contains("student_assignment"));
            assertTrue(xml.contains("ao.status &lt;&gt; 'ARCHIVED'"));

            assertTrue(xml.contains("id=\"findProblemRows\""));
            assertTrue(xml.contains("student_problem_state"));
            assertTrue(xml.contains("assignment_problem"));
            assertTrue(xml.contains("code_artifact.text_content AS code"));
            assertTrue(xml.contains("ORDER BY ap.sort_order, ap.id"));

            String normalized = xml.toLowerCase();
            assertTrue(!normalized.contains("from experiment"));
            assertTrue(!normalized.contains("join experiment"));
            assertTrue(!normalized.contains("from submission"));
            assertTrue(!normalized.contains("join submission"));
            assertTrue(!normalized.contains("from score"));
            assertTrue(!normalized.contains("join score"));
            assertTrue(!normalized.contains("submit_situation"));
        }
    }
}
