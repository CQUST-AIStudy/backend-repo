package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.LeetCodeSubmissionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface LeetCodeSubmissionRecordDao {
    int insert(LeetCodeSubmissionRecord record);
    Map<String, Object> countByStudentId(@Param("studentId") Integer studentId);
    List<LeetCodeSubmissionRecord> findTimelineByStudentId(
            @Param("studentId") Integer studentId, @Param("limit") Integer limit);
}
