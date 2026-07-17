package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.AiExperimentReport;
import org.apache.ibatis.annotations.Param;

public interface AiExperimentReportDao {

    AiExperimentReport findByOfferingAndStudent(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    int upsert(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("report") String report);
}
