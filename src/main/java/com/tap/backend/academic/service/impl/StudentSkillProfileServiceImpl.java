package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.StudentSkillStateDao;
import com.tap.backend.academic.entity.StudentSkillState;
import com.tap.backend.academic.service.StudentSkillProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 学生技能画像服务实现
 */
@Service
public class StudentSkillProfileServiceImpl implements StudentSkillProfileService {

    private static final Logger logger = LoggerFactory.getLogger(StudentSkillProfileServiceImpl.class);

    @Autowired
    private StudentSkillStateDao skillStateDao;

    @Override
    @Transactional
    public void updateSkillState(Integer studentId, String tagName, boolean isSuccess, int attemptCount) {
        try {
            StudentSkillState skillState = skillStateDao.findByStudentIdAndTag(studentId, tagName);
            
            if (skillState == null) {
                // 创建新的技能状态
                skillState = new StudentSkillState(studentId, tagName);
            }
            
            // 更新统计数据
            skillState.setAttemptCount(skillState.getAttemptCount() + attemptCount);
            if (isSuccess) {
                skillState.setSuccessCount(skillState.getSuccessCount() + 1);
            }
            
            // 计算平均尝试次数
            if (skillState.getSuccessCount() > 0) {
                double avgAttempts = (double) skillState.getAttemptCount() / skillState.getSuccessCount();
                skillState.setAvgAttemptsToSuccess(BigDecimal.valueOf(avgAttempts));
            }
            
            // 更新掌握度
            updateMasteryScore(skillState, isSuccess, attemptCount);
            
            // 更新遗忘度（重新练习会降低遗忘度）
            updateForgettingScore(skillState, true);
            
            // 更新置信度
            updateConfidenceScore(skillState);
            
            // 更新最后练习时间
            skillState.setLastPracticeAt(LocalDateTime.now());
            
            // 保存到数据库
            skillStateDao.insertOrUpdate(skillState);
            
            logger.debug("更新学生 {} 的技能 {} 状态: 成功={}, 尝试次数={}, 掌握度={}", 
                        studentId, tagName, isSuccess, attemptCount, skillState.getMasteryScore());
            
        } catch (Exception e) {
            logger.error("更新学生技能状态失败: studentId={}, tagName={}", studentId, tagName, e);
            throw new RuntimeException("更新技能状态失败", e);
        }
    }

    @Override
    @Transactional
    public void batchUpdateSkillStates(Integer studentId, List<SkillUpdate> skillUpdates) {
        for (SkillUpdate update : skillUpdates) {
            updateSkillState(studentId, update.getTagName(), update.isSuccess(), update.getAttemptCount());
        }
    }

    @Override
    public List<StudentSkillState> getStudentSkillProfile(Integer studentId) {
        return skillStateDao.findByStudentId(studentId);
    }

    @Override
    @Transactional
    public void initializeStudentSkillProfile(Integer studentId, List<String> tagNames) {
        List<StudentSkillState> skillStates = new ArrayList<>();
        
        for (String tagName : tagNames) {
            StudentSkillState existing = skillStateDao.findByStudentIdAndTag(studentId, tagName);
            if (existing == null) {
                StudentSkillState skillState = new StudentSkillState(studentId, tagName);
                skillStates.add(skillState);
            }
        }
        
        if (!skillStates.isEmpty()) {
            skillStateDao.batchInsertOrUpdate(skillStates);
            logger.info("为学生 {} 初始化了 {} 个技能状态", studentId, skillStates.size());
        }
    }

    @Override
    @Transactional
    public int updateForgettingScores(int daysSinceLastPractice) {
        try {
            List<StudentSkillState> skillsToUpdate = skillStateDao.findSkillsNeedForgettingUpdate(daysSinceLastPractice);
            
            for (StudentSkillState skill : skillsToUpdate) {
                updateForgettingScore(skill, false);
                skillStateDao.update(skill);
            }
            
            logger.info("更新了 {} 个技能的遗忘度", skillsToUpdate.size());
            return skillsToUpdate.size();
            
        } catch (Exception e) {
            logger.error("批量更新遗忘度失败", e);
            return 0;
        }
    }

    // 更新掌握度
    private void updateMasteryScore(StudentSkillState skillState, boolean isSuccess, int attemptCount) {
        double currentMastery = skillState.getMasteryScore().doubleValue();
        double delta;
        
        if (isSuccess) {
            // 成功：提高掌握度，但有上限
            delta = Math.max(5.0, 20.0 / attemptCount); // 尝试次数越少，提升越多
            delta = Math.min(delta, 100.0 - currentMastery); // 不超过100
        } else {
            // 失败：降低掌握度
            delta = -Math.min(3.0, currentMastery * 0.1); // 最多降低10%，但不少于3分
        }
        
        double newMastery = Math.max(0.0, Math.min(100.0, currentMastery + delta));
        skillState.setMasteryScore(BigDecimal.valueOf(newMastery));
    }

    // 更新遗忘度
    private void updateForgettingScore(StudentSkillState skillState, boolean isPracticing) {
        double currentForgetting = skillState.getForgettingScore().doubleValue();
        
        if (isPracticing) {
            // 练习时降低遗忘度
            double newForgetting = Math.max(0.0, currentForgetting - 10.0);
            skillState.setForgettingScore(BigDecimal.valueOf(newForgetting));
        } else {
            // 长时间未练习时增加遗忘度
            LocalDateTime lastPractice = skillState.getLastPracticeAt();
            if (lastPractice != null) {
                long daysSince = java.time.Duration.between(lastPractice, LocalDateTime.now()).toDays();
                double forgettingIncrease = Math.min(2.0 * daysSince, 30.0); // 每天增加2分，最多30分
                double newForgetting = Math.min(100.0, currentForgetting + forgettingIncrease);
                skillState.setForgettingScore(BigDecimal.valueOf(newForgetting));
            }
        }
    }

    // 更新置信度
    private void updateConfidenceScore(StudentSkillState skillState) {
        int totalAttempts = skillState.getAttemptCount();
        double successRate = skillState.getSuccessRate();
        
        // 置信度基于样本量和成功率
        double sampleConfidence = Math.min(totalAttempts * 2.0, 50.0); // 样本量贡献，最多50分
        double performanceConfidence = successRate * 50.0; // 成功率贡献，最多50分
        
        double newConfidence = sampleConfidence + performanceConfidence;
        skillState.setConfidenceScore(BigDecimal.valueOf(newConfidence));
    }
}