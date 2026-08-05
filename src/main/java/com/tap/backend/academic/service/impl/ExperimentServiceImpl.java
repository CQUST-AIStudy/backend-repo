package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.ExperimentDao;
import com.tap.backend.academic.dao.ScoreDao;
import com.tap.backend.academic.dao.SubmissionDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Score;
import com.tap.backend.academic.entity.Submission;
import com.tap.backend.academic.service.ExperimentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ExperimentServiceImpl implements ExperimentService {

    @Autowired
    private ExperimentDao experimentDao;

    @Autowired
    private ScoreDao scoreDao;

    @Autowired
    private SubmissionDao submissionDao;

    @Override
    public List<Experiment> findAllExperiments() {
        return experimentDao.findAllExperiments();
    }

    @Override
    public Experiment findExperimentById(int id) {
        return experimentDao.findExperimentById(id);
    }

    @Override
    public Experiment findExperimentByNum(int num) {
        return experimentDao.findExperimentByNum(num);
    }

    @Override
    public List<Experiment> findExperimentsByTeacherId(String teacherId) {
        return experimentDao.findExperimentsByTeacherId(teacherId);
    }

    @Override
    public List<Experiment> findExperimentsByClassKeyword(String classKeyword, String teacherId) {
        return experimentDao.findExperimentsByClassKeyword(classKeyword, teacherId);
    }

    @Override
    public Experiment findRecentDuplicateExperiment(Experiment experiment) {
        if (experiment == null) {
            return null;
        }
        List<Experiment> matches = experimentDao.findRecentDuplicateExperiments(
                experiment.getTeacherId(),
                experiment.getName(),
                experiment.getDeadline(),
                experiment.getClassName(),
                experiment.getDescribe(),
                experiment.getRequirements()
        );
        return matches == null || matches.isEmpty() ? null : matches.get(0);
    }

    @Override
    public boolean saveExperiment(Experiment experiment) {
        return experimentDao.saveExperiment(experiment) > 0;
    }

    @Override
    public boolean updateExperiment(Experiment experiment) {
        return experimentDao.updateExperiment(experiment) > 0;
    }

    @Override
    public boolean deleteExperiment(int id) {
        return experimentDao.deleteExperiment(id) > 0;
    }

    @Override
    @Transactional
    public boolean submitExperiment(int id, String username, String code, String report) {
        try {
            Experiment experiment = experimentDao.findExperimentById(id);
            if (experiment == null) {
                return false;
            }

            Submission submission = new Submission();
            submission.setUsername(username);
            submission.setExperiment_id(id);
            submission.setCode(code);
            submission.setReport(report);
            submission.setSubmit_time(new Date());
            submissionDao.saveSubmission(submission);

            Score score = scoreDao.findByUsernameAndExperimentNum(username, experiment.getNum());
            if (score == null) {
                score = new Score();
                score.setUsername(username);
                score.setExperiment_id(experiment.getExperiment_id());
                score.setNum(experiment.getNum());
                score.setSubmit_time(new Date());
                score.setStatus("pending_grading");
                score.setScore(null);
                score.setPlagiarism_rate(null);
                scoreDao.saveScore(score);
            } else {
                score.setSubmit_time(new Date());
                score.setStatus("pending_grading");
                score.setScore(null);
                score.setPlagiarism_rate(null);
                scoreDao.updateScore(score);
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> findExperimentsByUsername(String username) {
        List<Map<String, Object>> result = new ArrayList<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        List<Experiment> allExperiments = experimentDao.findAllExperiments();
        for (Experiment experiment : allExperiments) {
            Score score = scoreDao.findByUsernameAndExperimentNum(username, experiment.getNum());

            Map<String, Object> experimentInfo = new HashMap<>();
            experimentInfo.put("id", experiment.getExperiment_id());
            experimentInfo.put("num", experiment.getNum());
            experimentInfo.put("name", experiment.getName());
            experimentInfo.put("deadline", experiment.getDeadline());
            experimentInfo.put("description", experiment.getDescribe());

            if (score != null) {
                experimentInfo.put("status", score.getStatus());
                experimentInfo.put("score", score.getScore());
                experimentInfo.put("plagiarismRate", score.getPlagiarism_rate());
                if (score.getSubmit_time() != null) {
                    experimentInfo.put("submitTime", dateFormat.format(score.getSubmit_time()));
                }
            } else {
                experimentInfo.put("status", "not_started");
                experimentInfo.put("score", null);
                experimentInfo.put("plagiarismRate", null);
            }

            result.add(experimentInfo);
        }

        return result;
    }

    @Override
    public Experiment updateExperimentFromMap(int id, Map<String, Object> body) {
        Experiment experiment = experimentDao.findExperimentById(id);
        if (experiment == null) return null;

        applyIfPresent(body, "name", experiment::setName);
        applyIfPresent(body, "title", experiment::setName);
        applyIfPresent(body, "deadline", experiment::setDeadline);
        applyIfPresent(body, "description", experiment::setDescribe);
        applyIfPresent(body, "className", experiment::setClassName);

        experimentDao.updateExperiment(experiment);
        return experiment;
    }

    @Override
    public List<Experiment> searchExperiments(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return experimentDao.findAllExperiments();
        }
        List<Experiment> all = experimentDao.findAllExperiments();
        String kw = keyword.trim().toLowerCase();
        List<Experiment> result = new ArrayList<>();
        for (Experiment e : all) {
            if ((e.getName() != null && e.getName().toLowerCase().contains(kw))
                    || (e.getDescribe() != null && e.getDescribe().toLowerCase().contains(kw))
                    || (e.getClassName() != null && e.getClassName().toLowerCase().contains(kw))) {
                result.add(e);
            }
        }
        return result;
    }

    private void applyIfPresent(Map<String, Object> body, String key, java.util.function.Consumer<String> setter) {
        Object value = body.get(key);
        if (value == null) return;
        String str = String.valueOf(value).trim();
        if (!str.isEmpty()) setter.accept(str);
    }
}
