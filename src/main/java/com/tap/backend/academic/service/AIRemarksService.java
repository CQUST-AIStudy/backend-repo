package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.AIRemarks;

/**
 * AI评语服务接口
 */
public interface AIRemarksService {
    
    /**
     * 根据实验ID生成AI评语
     * @param experimentId 实验ID
     * @return AI生成的评语
     */
    String generateRemarks(Long experimentId);
    
    /**
     * 根据提交ID生成AI评语
     * @param submissionId 提交ID
     * @return AI生成的评语
     */
    String generateRemarksBySubmission(Long submissionId);
    
    /**
     * 获取学生和实验的AI备注
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return AI备注对象
     */
    AIRemarks getAIRemarkByStudentAndExperiment(String studentId, Integer experimentId);
    
    /**
     * 保存或更新AI备注
     * @param aiRemarks AI备注对象
     * @return 是否成功
     */
    boolean saveOrUpdateAIRemark(AIRemarks aiRemarks);
    
    /**
     * 删除AI备注
     * @param studentId 学号
     * @param experimentId 实验ID
     * @return 是否成功
     */
    boolean deleteAIRemark(String studentId, Integer experimentId);
}