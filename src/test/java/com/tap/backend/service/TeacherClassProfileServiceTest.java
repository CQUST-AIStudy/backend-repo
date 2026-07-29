package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.config.SkillTreeConfig;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.TeachingClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TeacherClassProfileServiceTest {

    @Mock TeachingClassRepository classRepository;
    @Mock EntityManager entityManager;
    @Mock Query rosterQuery;
    @Mock Query statsQuery;

    private TeacherClassProfileService service;

    @BeforeEach
    void setUp() {
        service = new TeacherClassProfileService(classRepository, new SkillTreeConfig());
        ReflectionTestUtils.setField(service, "em", entityManager);
    }

    @Test
    void profileUsesCurrentClassRosterWithLegacySixDimensionCalculation() {
        stubOwnedClass(7L, 42L);
        stubQueries(
                List.of(
                        new Object[] {"2025001", "张一"},
                        new Object[] {"2025002", "李二"},
                        new Object[] {"2025003", "王三"}),
                List.of(
                        stat("2025001", "张一", 1, 10, 8, 10),
                        stat("2025002", "李二", 1, 10, 0, 10),
                        stat("2025999", "班外学生", 1, 10, 10, 10)));

        Map<String, Object> result = service.getProfile(42L, 7L);

        assertEquals(3, result.get("totalStudents"));
        assertEquals("LEGACY_CLASS_PROFILE", map(result.get("quality")).get("status"));

        List<String> dimensions = stringList(result.get("dimensions"));
        assertEquals(List.of("线性表", "栈与队列", "树", "图", "哈希", "综合"), dimensions);
        assertFalse(dimensions.stream().anyMatch(name -> name.startsWith("待确认")));

        Map<String, Object> dimensionAvg = map(result.get("dimensionAvg"));
        assertEquals(42.7, dimensionAvg.get("线性表"));
        assertEquals(0.0, dimensionAvg.get("栈与队列"));

        Map<String, Object> tiers = map(result.get("tiers"));
        int tierTotal = toInt(map(tiers.get("A")).get("count"))
                + toInt(map(tiers.get("B")).get("count"))
                + toInt(map(tiers.get("C")).get("count"));
        assertEquals(3, tierTotal);
        assertFalse(tiers.containsKey("U"));

        List<Map<String, Object>> cStudents = list(map(tiers.get("C")).get("students"));
        assertTrue(cStudents.stream().anyMatch(row -> "2025001".equals(row.get("studentId"))));
        assertTrue(cStudents.stream().anyMatch(row -> "2025002".equals(row.get("studentId"))));
        assertTrue(cStudents.stream().anyMatch(row -> "2025003".equals(row.get("studentId"))));
        assertFalse(cStudents.stream().anyMatch(row -> "2025999".equals(row.get("studentId"))));

        verify(rosterQuery).setParameter("classId", 7L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, org.mockito.Mockito.times(2)).createNativeQuery(sql.capture());
        assertTrue(sql.getAllValues().get(0).contains("FROM class_member cm"));
        assertTrue(sql.getAllValues().get(1).contains("FROM submit_situation ss"));
        assertTrue(sql.getAllValues().get(1).contains("ss.student_id IN (:studentNos)"));
    }

    @Test
    void emptyRosterDoesNotQueryLegacySubmissionStats() {
        stubOwnedClass(7L, 42L);
        when(entityManager.createNativeQuery(anyString())).thenReturn(rosterQuery);
        when(rosterQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(rosterQuery);
        when(rosterQuery.getResultList()).thenReturn(List.of());

        Map<String, Object> result = service.getProfile(42L, 7L);

        assertEquals(0, result.get("totalStudents"));
        Map<String, Object> tiers = map(result.get("tiers"));
        assertEquals(0, map(tiers.get("A")).get("count"));
        assertEquals(0, map(tiers.get("B")).get("count"));
        assertEquals(0, map(tiers.get("C")).get("count"));
        verify(entityManager, org.mockito.Mockito.times(1)).createNativeQuery(anyString());
    }

    @Test
    void rejectsAccessToAnotherTeachersClassBeforeRunningAnalyticsQueries() {
        TeachingClassEntity teachingClass = mock(TeachingClassEntity.class);
        when(teachingClass.getTeacherId()).thenReturn(99L);
        when(classRepository.findById(7L)).thenReturn(Optional.of(teachingClass));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getProfile(42L, 7L));

        assertEquals(403, error.getStatusCode().value());
    }

    @Test
    void missingClassReturnsNotFound() {
        when(classRepository.findById(404L)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getProfile(42L, 404L));

        assertEquals(404, error.getStatusCode().value());
    }

    private void stubOwnedClass(Long classId, Long teacherId) {
        TeachingClassEntity teachingClass = mock(TeachingClassEntity.class);
        when(teachingClass.getId()).thenReturn(classId);
        when(teachingClass.getTeacherId()).thenReturn(teacherId);
        when(teachingClass.getName()).thenReturn("计科25-1");
        when(teachingClass.getCourseId()).thenReturn(9L);
        when(teachingClass.getCourseName()).thenReturn("数据结构");
        when(classRepository.findById(classId)).thenReturn(Optional.of(teachingClass));
    }

    private void stubQueries(List<Object[]> roster, List<Object[]> stats) {
        when(entityManager.createNativeQuery(anyString())).thenReturn(rosterQuery, statsQuery);
        when(rosterQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(rosterQuery);
        when(statsQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(statsQuery);
        when(rosterQuery.getResultList()).thenReturn(roster);
        when(statsQuery.getResultList()).thenReturn(stats);
    }

    private Object[] stat(String studentId, String studentName, int experimentId, long total, long ac, long questions) {
        return new Object[] {studentId, studentName, experimentId, "实验" + experimentId, total, ac, questions};
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        return (List<String>) value;
    }

    private int toInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}
