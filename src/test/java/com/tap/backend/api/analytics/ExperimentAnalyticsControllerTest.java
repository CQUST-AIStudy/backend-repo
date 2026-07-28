package com.tap.backend.api.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.security.TeacherSessionResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ExperimentAnalyticsControllerTest {

    @Mock TeacherSessionResolver teacherSessionResolver;
    @Mock EntityManager entityManager;
    @Mock Query query;
    @Mock HttpServletRequest request;

    private ExperimentAnalyticsController controller;

    @BeforeEach
    void setUp() {
        controller = new ExperimentAnalyticsController(teacherSessionResolver);
        ReflectionTestUtils.setField(controller, "em", entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    }

    @Test
    void listExperimentsRestrictsAndReturnsCurrentClassCourse() {
        stubTeacher();
        stubNamedParameters();
        when(query.getResultList()).thenReturn(Collections.singletonList(new Object[] {
                101, "链表实验", "计科25", 7L, "计科25-1", 9L, "数据结构", 35, 30, 6
        }));

        List<Map<String, Object>> result = controller.listExperiments(
                "计科25", 7L, 9L, "忽略的课程名", request).data();

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).get("classId"));
        assertEquals(9L, result.get(0).get("courseId"));
        assertEquals("数据结构", result.get(0).get("courseName"));
        verify(query).setParameter("teacherId", 42);
        verify(query).setParameter("classId", 7L);
        verify(query).setParameter("courseId", 9L);
        verify(query).setParameter("classPrefix", "计科25");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("ao.class_id = :classId"));
        assertTrue(sql.getValue().contains("tc.course_id = :courseId"));
        assertTrue(sql.getValue().contains("tc.teacher_id = :teacherId"));
    }

    @Test
    void listExperimentsFiltersOfferingsFromAnotherRecognizableCourse() {
        stubTeacher();
        stubNamedParameters();
        when(query.getResultList()).thenReturn(List.of(
                new Object[] {101, "计科25数据结构期中考试重现", "计科25", 7L, "计科25", 9L, "数据结构", 35, 30, 6},
                new Object[] {102, "2025级C语言实验4（计数器控制循环）", "计科25", 7L, "计科25", 9L, "数据结构", 35, 30, 6}
        ));

        List<Map<String, Object>> result = controller.listExperiments(
                null, 7L, null, null, request).data();

        assertEquals(1, result.size());
        assertEquals(101, result.get(0).get("experimentId"));
        assertEquals("计科25数据结构期中考试重现", result.get(0).get("name"));
    }

    @Test
    void classPrefixesReturnActiveClassesWithOfferings() {
        stubTeacher();
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(new Object[] {7L, "计科25-1"}));

        List<Map<String, Object>> result = controller.getClassPrefixes(request).data();

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).get("classId"));
        assertEquals("计科25-1", result.get(0).get("name"));
        verify(query).setParameter("teacherId", 42);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("FROM teaching_class tc"));
        assertTrue(sql.getValue().contains("EXISTS (SELECT 1 FROM assignment_offering ao"));
        assertTrue(sql.getValue().contains("tc.teacher_id = :teacherId"));
    }

    @Test
    void legacyProblemAccuracyRestrictsStudentsToCurrentOffering() {
        when(query.setParameter(anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(
                List.of(501),
                Collections.singletonList(new Object[] {"2-1", "链表", 10, 8, 1, 0, 0}));

        List<Map<String, Object>> result = ReflectionTestUtils.invokeMethod(
                controller, "computeLegacyProblemAccuracy", 101);

        assertEquals(1, result.size());
        assertEquals("2-1", result.get(0).get("label"));
        verify(query).setParameter(2, 101);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(2)).createNativeQuery(sql.capture());
        String scopedQuery = sql.getAllValues().get(1);
        assertTrue(scopedQuery.contains("JOIN student_profile sp"));
        assertTrue(scopedQuery.contains("sa.offering_id = ?2"));
    }

    @Test
    void legacyFullScoreRestrictsStudentsToCurrentOffering() {
        when(query.setParameter(anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(501), List.of(100));

        Double result = ReflectionTestUtils.invokeMethod(controller, "queryLegacyFullScore", 101);

        assertEquals(100.0, result);
        verify(query).setParameter(2, 101);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(2)).createNativeQuery(sql.capture());
        String scopedQuery = sql.getAllValues().get(1);
        assertTrue(scopedQuery.contains("JOIN student_profile sp"));
        assertTrue(scopedQuery.contains("sa.offering_id = ?2"));
    }

    @Test
    void overviewBatchIncludesDifficultyAndDiscrimination() {
        when(query.setParameter(anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(new Object[] {
                101, 35, 30, 28, 30, 95, 80, 90, 60, 100
        }));

        Map<Integer, Map<String, Object>> result = ReflectionTestUtils.invokeMethod(
                controller, "computeOverviewBatch", List.of(101));

        assertEquals(80.0, result.get(101).get("avgScore"));
        assertEquals(0.2, result.get(101).get("difficulty"));
        assertEquals(0.3, result.get(101).get("discrimination"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertTrue(sql.getValue().contains("PARTITION BY offering_id"));
        assertTrue(sql.getValue().contains("top_avg"));
        assertTrue(sql.getValue().contains("bottom_avg"));
    }

    private void stubTeacher() {
        when(teacherSessionResolver.requireCurrentTeacher(request))
                .thenReturn(new Teacher(42, "Teacher", null, null));
    }

    private void stubNamedParameters() {
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    }
}
