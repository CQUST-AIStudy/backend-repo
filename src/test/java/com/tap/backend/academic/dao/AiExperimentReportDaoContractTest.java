package com.tap.backend.academic.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tap.backend.academic.entity.AiExperimentReport;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

class AiExperimentReportDaoContractTest {

    @Test
    void definesOfferingAndStudentReportLookup() throws Exception {
        Method method = AiExperimentReportDao.class.getMethod(
                "findByOfferingAndStudent", long.class, long.class);

        assertEquals(AiExperimentReport.class, method.getReturnType());
        assertEquals("offeringId", method.getParameters()[0].getAnnotation(Param.class).value());
        assertEquals("studentId", method.getParameters()[1].getAnnotation(Param.class).value());
    }

    @Test
    void definesReportUpsert() throws Exception {
        Method method = AiExperimentReportDao.class.getMethod(
                "upsert", long.class, long.class, String.class);

        assertEquals(int.class, method.getReturnType());
        assertEquals("offeringId", method.getParameters()[0].getAnnotation(Param.class).value());
        assertEquals("studentId", method.getParameters()[1].getAnnotation(Param.class).value());
        assertEquals("report", method.getParameters()[2].getAnnotation(Param.class).value());
    }

    @Test
    void definesSqlMappingsForUnifiedReportPersistence() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mappers/AiExperimentReportMapper.xml")) {
            assertNotNull(stream, "AiExperimentReportMapper.xml should be on the classpath");
            String xml = new String(stream.readAllBytes());

            assertTrue(xml.contains("id=\"findByOfferingAndStudent\""));
            assertTrue(xml.contains("FROM ai_experiment_report"));
            assertTrue(xml.contains("id=\"upsert\""));
            assertTrue(xml.contains("INSERT INTO ai_experiment_report"));
            assertTrue(xml.contains("ON DUPLICATE KEY UPDATE"));
        }
    }
}
