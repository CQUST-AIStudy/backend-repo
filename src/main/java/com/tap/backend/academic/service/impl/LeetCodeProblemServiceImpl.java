package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.LeetCodeProblemDao;
import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.service.LeetCodeProblemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * LeetCode题目服务实现
 */
@Service
public class LeetCodeProblemServiceImpl implements LeetCodeProblemService {

    @Autowired
    private LeetCodeProblemDao problemDao;

    @Override
    public LeetCodeProblem findById(Long id) {
        return problemDao.findById(id);
    }

    @Override
    public LeetCodeProblem findByProblemCode(String problemCode) {
        return problemDao.findByProblemCode(problemCode);
    }

    @Override
    public List<LeetCodeProblem> findAll() {
        return problemDao.findAll();
    }

    @Override
    public List<LeetCodeProblem> findByDifficulty(String difficulty) {
        return problemDao.findByDifficulty(difficulty);
    }

    @Override
    public void save(LeetCodeProblem problem) {
        problemDao.insert(problem);
    }

    @Override
    public void update(LeetCodeProblem problem) {
        problemDao.update(problem);
    }

    @Override
    public void deleteById(Long id) {
        problemDao.deleteById(id);
    }
}