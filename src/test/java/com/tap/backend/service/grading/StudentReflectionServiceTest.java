package com.tap.backend.service.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.dao.StudentExperimentReflectionDao;
import com.tap.backend.academic.entity.StudentExperimentReflection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StudentReflectionServiceTest {
    @Mock StudentExperimentReflectionDao reflectionDao;

    @Test
    void extractsNumberedExperimentSummaryUntilNextHeading() {
        String markdown = "## 实验内容\n完成链表操作。\n\n## 六、实验总结\n掌握了链表遍历。\n还需检查边界。\n\n## 附录\n忽略";
        assertEquals("掌握了链表遍历。\n还需检查边界。", StudentReflectionService.extractSummary(markdown));
    }

    @Test
    void generatedAiReportPersistsSummaryField() {
        StudentReflectionService service = new StudentReflectionService(reflectionDao);
        service.saveFromAiReport(7L, 2L, "## 实验总结\n掌握了循环链表的基本操作。");
        verify(reflectionDao).upsert(7L, 2L, "掌握了循环链表的基本操作。", "AI_REPORT");
    }

    @Test
    void teacherReadsPersistedReflectionWithoutRuntimeSupplement() {
        StudentExperimentReflection entity = new StudentExperimentReflection();
        entity.setReflectionText("数据库中已经保存的心得。");
        entity.setSource("SYSTEM_BACKFILL");
        when(reflectionDao.findByOfferingAndStudent(8L, 3L)).thenReturn(entity);

        Map<String, Object> view = new StudentReflectionService(reflectionDao).find(8L, 3L);
        assertEquals("数据库中已经保存的心得。", view.get("content"));
        assertEquals("系统补充心得 · 已保存", view.get("sourceLabel"));
    }

    @Test
    void absentDatabaseRowReturnsNullInsteadOfGeneratedText() {
        when(reflectionDao.findByOfferingAndStudent(9L, 4L)).thenReturn(null);
        assertNull(new StudentReflectionService(reflectionDao).find(9L, 4L));
    }
}
