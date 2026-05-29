package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.Score;
import java.util.List;

public interface ScoreService {


    // 根据实验ID查找成绩
    List<Score> findByExperimentId(int experimentId);

    
    /**
     * 查找指定用户和实验的成绩
     * @param username 用户名
     * @param experimentNum 实验编号
     * @return 成绩对象
     */
    Score findByUsernameAndExperimentNum(String username, int experimentNum);
    
    /**
     * 保存成绩
     * @param score 成绩对象
     * @return 是否成功
     */
    boolean saveScore(Score score);
    
    /**
     * 更新成绩
     * @param score 成绩对象
     * @return 是否成功
     */
    boolean updateScore(Score score);
    
    /**
     * 查找指定用户的所有成绩
     * @param username 用户名
     * @return 成绩列表
     */
    List<Score> findScoresByUsername(String username);


    //根据姓名来查找每个实验的总成绩
    List<Score> findPerExperimentSumScoresByUsername(String username);


    //根据学生ID和实验ID来查找实验的抄袭率
    String getexperimentPlagiarismRate(int studentId, int experimentId);
}