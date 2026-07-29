package com.tap.backend.service.practice.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PtaWrongQuestionQueryServiceTest {

  @Mock EntityManager entityManager;
  @Mock Query query;

  private PtaWrongQuestionQueryService service;

  @BeforeEach
  void setUp() {
    service = new PtaWrongQuestionQueryService(entityManager);
    when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
  }

  @Test
  void returnsNativePtaErrorsWithKnowledgePointsAndCourseScope() {
    when(query.getResultList()).thenReturn(List.of(
        new Object[] {11L, 101L, 3L, 1L, 2L, "\u4e8c\u53c9\u6811\u904d\u5386", "7-1", "pta-101",
            "\u8ba1\u79d125\u6570\u636e\u7ed3\u6784\u5b9e\u9a8c", "set-1", "\u6811", "\u6570\u636e\u7ed3\u6784/\u6811", "\u4e2d\u7b49", null, "\u6570\u636e\u7ed3\u6784"},
        new Object[] {12L, 102L, 2L, 0L, 2L, "\u5faa\u73af", "2-1", "pta-102",
            "C\u8bed\u8a00\u5b9e\u9a8c", "set-2", "\u5faa\u73af", "C\u8bed\u8a00/\u5faa\u73af", "\u7b80\u5355", null, "\u6570\u636e\u7ed3\u6784"}
    ));

    Map<String, Object> result = service.list("20250001", 7L, 1);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    assertEquals(1, items.size());
    assertEquals(101L, items.get(0).get("problem_id"));
    assertEquals("\u6811", items.get(0).get("knowledge_point"));
    assertEquals(2, items.get(0).get("error_count"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(entityManager).createNativeQuery(sql.capture());
    assertTrue(sql.getValue().contains("FROM student_problem_attempt spa"));
    assertTrue(sql.getValue().contains("LEFT JOIN pta_problem_detail apd"));
    assertTrue(sql.getValue().contains("ao.class_id = :classId"));
    assertFalse(sql.getValue().contains("leetcode_problem_bank"));
    verify(query).setParameter("studentNo", "20250001");
    verify(query).setParameter("classId", 7L);
  }
}
