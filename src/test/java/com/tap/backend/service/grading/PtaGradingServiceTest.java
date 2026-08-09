package com.tap.backend.service.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.PtaGradingResultEntity;
import com.tap.backend.repo.PtaGradingResultRepository;
import com.tap.backend.security.TeacherPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.NotificationService;
import com.tap.backend.service.animation.AnimationAiClient;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtaGradingServiceTest {

    @Mock PtaGradingResultRepository repository;
    @Mock TeacherPrincipalResolver teacherPrincipalResolver;
    @Mock AnimationAiClient aiClient;
    @Mock NotificationService notificationService;
    @Mock StudentReflectionService studentReflectionService;

    private PtaGradingService service() {
        return new PtaGradingService(repository, teacherPrincipalResolver, aiClient,
                notificationService, new ObjectMapper(), studentReflectionService);
    }

    // 客观分：Σbest/Σmax×100
    @Test
    void scoreNormalizesByMaxScore() {
        PtaGradingService.StudentAggregate a = new PtaGradingService.StudentAggregate(1L, "S001", "张三");
        a.add(Map.of(), new BigDecimal("10"), new BigDecimal("10"), true);   // 满分
        a.add(Map.of(), new BigDecimal("10"), new BigDecimal("5"), false);   // 半分
        assertEquals(new BigDecimal("75.00"), a.score());
        assertEquals(new BigDecimal("50.00"), a.acRate());
    }

    // 无满分字段时回退 AC 率×100
    @Test
    void scoreFallsBackToAcRateWhenNoMaxScore() {
        PtaGradingService.StudentAggregate a = new PtaGradingService.StudentAggregate(2L, "S002", "李四");
        a.add(Map.of(), null, null, true);
        a.add(Map.of(), null, null, true);
        a.add(Map.of(), null, null, false);
        assertEquals(new BigDecimal("66.67"), a.score());
    }

    // 学生未发布 → published:false
    @Test
    void studentSeesNothingWhenUnpublished() {
        when(repository.findFirstByOfferingIdAndStudentNoAndPublishedTrue(7L, "S001"))
                .thenReturn(Optional.empty());
        Map<String, Object> r = service().getPublishedForStudent(7L, "S001");
        assertEquals(Boolean.FALSE, r.get("published"));
    }

    // detail 记录不存在 → 404
    @Test
    void detailNotFoundThrows404() {
        when(teacherPrincipalResolver.requireTeacherId(org.mockito.ArgumentMatchers.any())).thenReturn(42L);
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> service().detail(9L, (UserPrincipal) null));
    }

    // 学生已发布 → 返回分数与评语
    @Test
    void studentSeesPublishedResult() {
        PtaGradingResultEntity e = new PtaGradingResultEntity();
        e.setId(3L);
        e.setOfferingId(7L);
        e.setStudentNo("S001");
        e.setPublished(true);
        e.setScore(new BigDecimal("88.00"));
        e.setComment("整体不错，注意边界处理。");
        when(repository.findFirstByOfferingIdAndStudentNoAndPublishedTrue(7L, "S001"))
                .thenReturn(Optional.of(e));
        Map<String, Object> r = service().getPublishedForStudent(7L, "S001");
        assertEquals(Boolean.TRUE, r.get("published"));
        assertEquals(new BigDecimal("88.00"), r.get("score"));
        assertEquals("整体不错，注意边界处理。", r.get("comment"));
        assertFalse(String.valueOf(r.get("comment")).isBlank());
    }
}
