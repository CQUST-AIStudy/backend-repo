package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.AIRemarksMapper;
import com.tap.backend.academic.entity.AIRemarks;
import com.tap.backend.academic.service.AIRemarksService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI备注服务实现类
 */
@Service
public class AIRemarksServiceImpl implements AIRemarksService {
    
    private static final Logger logger = LoggerFactory.getLogger(AIRemarksServiceImpl.class);

    @Autowired
    private AIRemarksMapper aiRemarksMapper;

    @Override
    public AIRemarks getAIRemarkByStudentAndExperiment(Integer studentId, Integer experimentId) {
        logger.info("获取学生ID为{}，实验ID为{}的AI备注", studentId, experimentId);
        try {
            return aiRemarksMapper.getAIRemarkByStudentAndExperiment(studentId, experimentId);
        } catch (Exception e) {
            logger.error("获取AI备注时发生错误: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean saveOrUpdateAIRemark(AIRemarks aiRemarks) {
        logger.info("保存或更新AI备注: {}", aiRemarks);
        try {
            int result = aiRemarksMapper.saveOrUpdateAIRemark(aiRemarks);
            return result > 0;
        } catch (Exception e) {
            logger.error("保存或更新AI备注时发生错误: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean deleteAIRemark(Integer studentId, Integer experimentId) {
        logger.info("删除学生ID为{}，实验ID为{}的AI备注", studentId, experimentId);
        try {
            int result = aiRemarksMapper.deleteAIRemark(studentId, experimentId);
            return result > 0;
        } catch (Exception e) {
            logger.error("删除AI备注时发生错误: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String generateRemarks(Long experimentId) {
        logger.info("为实验ID {}生成AI评语", experimentId);
        try {
            // 这里实现生成评语的逻辑
            // 可以调用外部AI服务或使用本地模型生成评语
            return "这是为实验 " + experimentId + " 生成的AI评语，包含了对实验完成情况的评价。";
        } catch (Exception e) {
            logger.error("生成AI评语时发生错误: {}", e.getMessage(), e);
            return "生成评语时发生错误";
        }
    }

    @Override
    public String generateRemarksBySubmission(Long submissionId) {
        logger.info("为提交ID {}生成AI评语", submissionId);
        try {
            // 这里实现基于提交ID生成评语的逻辑
            return "这是为提交 " + submissionId + " 生成的AI评语，基于代码分析和执行结果。";
        } catch (Exception e) {
            logger.error("基于提交ID生成AI评语时发生错误: {}", e.getMessage(), e);
            return "生成评语时发生错误";
        }
    }
}