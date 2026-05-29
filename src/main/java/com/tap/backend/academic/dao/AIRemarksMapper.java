package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.AIRemarks;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/**
 * AI备注数据访问接口
 */
@Repository
public interface AIRemarksMapper {
    
    /**
     * 根据学生ID和实验ID查询AI备注
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return AI备注信息
     */
    AIRemarks getAIRemarkByStudentAndExperiment(Integer studentId, Integer experimentId);
    
    /**
     * 保存或更新AI备注
     * @param aiRemarks AI备注信息
     * @return 影响的行数
     */
    int saveOrUpdateAIRemark(AIRemarks aiRemarks);
    
    /**
     * 删除AI备注
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return 影响的行数
     */
    int deleteAIRemark(@Param("studentId") Integer studentId, @Param("experimentId") Integer experimentId);
}