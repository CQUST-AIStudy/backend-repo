package com.tap.backend.academic.dao;

import com.tap.backend.academic.teacherexperiment.AiReportContext;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StudentAiReportQueryDao {

    AiReportContext findContext(
            @Param("studentNo") String studentNo,
            @Param("offeringId") long offeringId);

    List<TeacherSubmissionProblemRow> findProblemRows(
            @Param("studentNo") String studentNo,
            @Param("offeringId") long offeringId);
}
