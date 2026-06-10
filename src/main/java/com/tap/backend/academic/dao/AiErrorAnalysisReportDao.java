package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.AiErrorAnalysisReport;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * AI错误分析报告数据访问接口
 * 对应 ai_error_analysis_report 表
 */
public interface AiErrorAnalysisReportDao {

    /**
     * 保存分析报告
     */
    int save(AiErrorAnalysisReport report);

    /**
     * 根据学生和实验查询所有报告
     */
    List<AiErrorAnalysisReport> findByStudentAndExperiment(
            @Param("studentNo") String studentNo,
            @Param("experimentId") int experimentId);

    /**
     * 根据分析ID查询单条报告
     */
    AiErrorAnalysisReport findByAnalysisId(@Param("analysisId") String analysisId);

    /**
     * 删除学生在指定实验的所有报告
     */
    int deleteByStudentAndExperiment(
            @Param("studentNo") String studentNo,
            @Param("experimentId") int experimentId);
}
