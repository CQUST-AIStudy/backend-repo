package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.Score;

public interface ScoreDao {
    
    /**
     * 根据用户名和实验编号查找成绩
     * @param username 用户名
     * @param experimentNum 实验编号
     * @return 成绩对象
     */
    Score findByUsernameAndExperimentNum(String username, int experimentNum);
    
    /**
     * 保存成绩信息
     * @param score 成绩对象
     * @return 影响的行数
     */
    int saveScore(Score score);
    
    /**
     * 更新成绩信息
     * @param score 成绩对象
     * @return 影响的行数
     */
    int updateScore(Score score);
    
    /**
     * 根据用户名查找所有成绩
     * @param username 用户名
     * @return 成绩列表
     */
    java.util.List<Score> findScoresByUsername(String username);

    //根据姓名来查找每个实验的总成绩
    java.util.List<Score> findPerExperimentSumScoresByUsername(String username);

    java.util.List<Score> findByExperimentId(int experimentId);

    //根据学生ID和实验ID来查找实验的抄袭率
    String getexperimentPlagiarismRate(int studentId, int experimentId);
}