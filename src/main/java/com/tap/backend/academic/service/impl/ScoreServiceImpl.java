package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.ScoreDao;
import com.tap.backend.academic.entity.Score;
import com.tap.backend.academic.service.ScoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScoreServiceImpl implements ScoreService {

    @Autowired
    private ScoreDao scoreDao;

    @Override
    public List<Score> findByExperimentId(int experimentId) {
        return scoreDao.findByExperimentId(experimentId);
    }

    @Override
    public Score findByUsernameAndExperimentNum(String username, int experimentNum) {
        return scoreDao.findByUsernameAndExperimentNum(username, experimentNum);
    }

    @Override
    public boolean saveScore(Score score) {
        return scoreDao.saveScore(score) > 0;
    }

    @Override
    public boolean updateScore(Score score) {
        return scoreDao.updateScore(score) > 0;
    }

    @Override
    public List<Score> findScoresByUsername(String username) {
        return scoreDao.findScoresByUsername(username);
    }


    //根据username来查找每个实验的总分
    @Override
    public List<Score> findPerExperimentSumScoresByUsername(String username) {
        return scoreDao.findPerExperimentSumScoresByUsername(username);
    }

    @Override
    public String getexperimentPlagiarismRate(int studentId, int experimentId) {
        return scoreDao.getexperimentPlagiarismRate(studentId, experimentId);
    }
}