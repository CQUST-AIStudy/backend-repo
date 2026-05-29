package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.StudentSkillState;

import java.util.List;

/**
 * 学生技能画像服务接口
 */
public interface StudentSkillProfileService {

    /**
     * 更新学生技能状态（基于练习结果）
     * @param studentId 学生ID
     * @param tagName 技能标签
     * @param isSuccess 是否成功
     * @param attemptCount 尝试次数
     */
    void updateSkillState(Integer studentId, String tagName, boolean isSuccess, int attemptCount);

    /**
     * 批量更新学生技能状态
     * @param studentId 学生ID
     * @param skillUpdates 技能更新列表
     */
    void batchUpdateSkillStates(Integer studentId, List<SkillUpdate> skillUpdates);

    /**
     * 获取学生技能画像
     * @param studentId 学生ID
     * @return 技能状态列表
     */
    List<StudentSkillState> getStudentSkillProfile(Integer studentId);

    /**
     * 初始化学生技能画像
     * @param studentId 学生ID
     * @param tagNames 技能标签列表
     */
    void initializeStudentSkillProfile(Integer studentId, List<String> tagNames);

    /**
     * 更新遗忘度（定时任务调用）
     * @param daysSinceLastPractice 距离上次练习的天数阈值
     * @return 更新的技能数量
     */
    int updateForgettingScores(int daysSinceLastPractice);

    /**
     * 技能更新数据类
     */
    class SkillUpdate {
        private String tagName;
        private boolean isSuccess;
        private int attemptCount;

        public SkillUpdate(String tagName, boolean isSuccess, int attemptCount) {
            this.tagName = tagName;
            this.isSuccess = isSuccess;
            this.attemptCount = attemptCount;
        }

        // Getters and Setters
        public String getTagName() { return tagName; }
        public void setTagName(String tagName) { this.tagName = tagName; }

        public boolean isSuccess() { return isSuccess; }
        public void setSuccess(boolean success) { isSuccess = success; }

        public int getAttemptCount() { return attemptCount; }
        public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    }
}