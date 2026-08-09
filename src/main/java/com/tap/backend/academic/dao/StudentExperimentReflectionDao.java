package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.StudentExperimentReflection;
import org.apache.ibatis.annotations.Param;

public interface StudentExperimentReflectionDao {
    StudentExperimentReflection findByOfferingAndStudent(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId);

    int upsert(
            @Param("offeringId") long offeringId,
            @Param("studentId") long studentId,
            @Param("reflectionText") String reflectionText,
            @Param("source") String source);
}
