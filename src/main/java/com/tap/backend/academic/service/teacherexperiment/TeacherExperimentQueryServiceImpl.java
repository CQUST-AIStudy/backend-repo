package com.tap.backend.academic.service.teacherexperiment;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.teacher.TeacherExperiment;
import com.tap.backend.academic.service.ExperimentService;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentPlagiarismRow;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentListResult;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentScoreAggregate;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentScoreRow;
import com.tap.backend.academic.teacherexperiment.TeacherExperimentSummaryRow;
import com.tap.backend.academic.teacherexperiment.TeacherStudentAssignmentRow;
import com.tap.backend.academic.teacherexperiment.TeacherStudentExperimentResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TeacherExperimentQueryServiceImpl implements TeacherExperimentQueryService {

    @Autowired
    private StudentDao studentDao;

    @Autowired
    private TeacherExperimentQueryDao teacherExperimentQueryDao;

    @Autowired
    private ExperimentService experimentService;

    @Value("${tap.teacher.read-path.unified-experiment-queries-enabled:true}")
    private boolean unifiedExperimentQueriesEnabled;

    @Override
    public TeacherExperimentListResult getTeacherExperimentList(Integer teacherId, Long classId, String classKeyword) {
        String normalizedClassKeyword = normalizeClassKeyword(classKeyword);
        if (hasText(normalizedClassKeyword)) {
            TeacherExperimentListResult legacyResult = getLegacyTeacherExperimentList(teacherId, classId, normalizedClassKeyword);
            if (!legacyResult.getExperiments().isEmpty()) {
                return legacyResult;
            }
        }

        if (!unifiedExperimentQueriesEnabled) {
            return getLegacyTeacherExperimentList(teacherId, classId, normalizedClassKeyword);
        }

        List<TeacherExperimentSummaryRow> summaries = teacherExperimentQueryDao
                .findTeacherExperimentSummaries(teacherId, classId, normalizedClassKeyword);
        if (summaries == null || summaries.isEmpty()) {
            return getLegacyTeacherExperimentList(teacherId, classId, normalizedClassKeyword);
        }
        List<TeacherExperiment> teacherExperiments = new ArrayList<>();
        int studentCount = 0;
        for (TeacherExperimentSummaryRow summary : summaries) {
            TeacherExperiment teacherExperiment = new TeacherExperiment(
                    summary.getExperimentId(),
                    summary.getName(),
                    summary.getDeadline(),
                    summary.getCreatedTime()
            );
            int submissionCount = summary.getSubmissionCount() == null
                    ? 0
                    : summary.getSubmissionCount();
            teacherExperiment.setSubmissionCount(submissionCount);
            double averageScore = summary.getAverageScore() == null
                    ? 0.0
                    : summary.getAverageScore();
            teacherExperiment.setAverageScore(averageScore);
            teacherExperiments.add(teacherExperiment);
            studentCount = Math.max(studentCount, summary.getRosterCount() == null ? 0 : summary.getRosterCount());
        }

        return new TeacherExperimentListResult(teacherExperiments, studentCount);
    }

    @Override
    public TeacherStudentExperimentResult getAllStudentExperiments(Integer teacherId, Long classId, String classKeyword, Integer experimentId) {
        String normalizedClassKeyword = normalizeClassKeyword(classKeyword);
        if (!unifiedExperimentQueriesEnabled) {
            return getLegacyAllStudentExperiments(teacherId, classId, normalizedClassKeyword, experimentId);
        }

        List<TeacherStudentAssignmentRow> assignments = teacherExperimentQueryDao
                .findTeacherStudentAssignments(teacherId, classId, normalizedClassKeyword, experimentId);
        if (assignments == null || assignments.isEmpty()) {
            if (hasText(normalizedClassKeyword) || experimentId != null) {
                return getLegacyAllStudentExperiments(teacherId, classId, normalizedClassKeyword, experimentId);
            }
            return new TeacherStudentExperimentResult(getStudentCount(teacherId) > 0, Collections.emptyList());
        }

        List<Map<String, Object>> rows = new ArrayList<>(assignments.size());
        for (TeacherStudentAssignmentRow assignment : assignments) {
            Map<String, Object> experimentData = new LinkedHashMap<>();
            experimentData.put("studentId", assignment.getStudentId());
            experimentData.put("studentName", assignment.getStudentName());
            experimentData.put(
                    "studentUsername",
                    hasText(assignment.getStudentUsername()) ? assignment.getStudentUsername() : assignment.getStudentId()
            );
            experimentData.put("classId", assignment.getClassId());
            experimentData.put("className", assignment.getClassName());
            experimentData.put("experimentId", assignment.getExperimentId());
            experimentData.put("experimentName", assignment.getExperimentName());
            experimentData.put("deadline", assignment.getDeadline());
            experimentData.put("status", mapUnifiedStatus(assignment.getSubmissionStatus()));
            experimentData.put("submitTime", assignment.getSubmitTime());
            experimentData.put("score", assignment.getScore() == null ? 0.0 : assignment.getScore());
            experimentData.put("submissionStatus", assignment.getSubmissionStatus());
            experimentData.put("completionEvidence", assignment.getCompletionEvidence());
            experimentData.put("transcriptRowPresent", Boolean.TRUE.equals(assignment.getTranscriptRowPresent()));
            experimentData.put("answerSheetCount", assignment.getAnswerSheetCount() == null ? 0 : assignment.getAnswerSheetCount());
            experimentData.put("scoredCodeCount", assignment.getScoredCodeCount() == null ? 0 : assignment.getScoredCodeCount());
            experimentData.put(
                    "submissionAttemptCount",
                    assignment.getSubmissionAttemptCount() == null ? 0 : assignment.getSubmissionAttemptCount()
            );
            experimentData.put(
                    "plagiarismRate",
                    roundTwoDecimals(calculateAveragePlagiarismRate(assignment.getPlagiarismRate()))
            );
            rows.add(experimentData);
        }

        return new TeacherStudentExperimentResult(true, rows);
    }

    private TeacherExperimentListResult getLegacyTeacherExperimentList(Integer teacherId, Long classId, String classKeyword) {
        List<TeacherStudentAssignmentRow> roster = teacherExperimentQueryDao.findTeacherStudentRoster(teacherId, classId);
        int studentCount = roster == null ? 0 : roster.size();

        List<Experiment> experiments = loadLegacyExperiments(teacherId, classKeyword);
        if (experiments == null || experiments.isEmpty()) {
            return new TeacherExperimentListResult(Collections.emptyList(), studentCount);
        }

        List<Integer> experimentIds = experiments.stream()
                .filter(Objects::nonNull)
                .map(Experiment::getExperiment_id)
                .collect(Collectors.toList());

        Map<Integer, TeacherExperimentScoreAggregate> aggregateByExperimentId = teacherExperimentQueryDao
                .summarizeByExperimentIds(experimentIds)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        TeacherExperimentScoreAggregate::getExperimentId,
                        aggregate -> aggregate,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<TeacherExperiment> teacherExperiments = new ArrayList<>(experiments.size());
        for (Experiment experiment : experiments) {
            TeacherExperiment teacherExperiment = new TeacherExperiment(
                    experiment.getExperiment_id(),
                    experiment.getName(),
                    experiment.getDeadline(),
                    experiment.getCreatedAt()
            );
            TeacherExperimentScoreAggregate aggregate = aggregateByExperimentId.get(experiment.getExperiment_id());
            int submissionCount = aggregate == null || aggregate.getSubmissionCount() == null
                    ? 0
                    : aggregate.getSubmissionCount();
            double averageScore = studentCount <= 0 || aggregate == null || aggregate.getTotalPositiveScore() == null
                    ? 0.0
                    : roundTwoDecimals((double) aggregate.getTotalPositiveScore() / studentCount);
            teacherExperiment.setSubmissionCount(submissionCount);
            teacherExperiment.setAverageScore(averageScore);
            teacherExperiments.add(teacherExperiment);
        }

        return new TeacherExperimentListResult(teacherExperiments, studentCount);
    }

    private TeacherStudentExperimentResult getLegacyAllStudentExperiments(Integer teacherId, Long classId, String classKeyword, Integer experimentId) {
        List<TeacherStudentAssignmentRow> roster = teacherExperimentQueryDao.findTeacherStudentRoster(teacherId, classId);

        // 兜底：class_member 没有学生记录，但 experimentId 指定了，直接从 submit_situation 查学生
        if ((roster == null || roster.isEmpty()) && experimentId != null) {
            roster = teacherExperimentQueryDao.findStudentRosterFromSubmitSituation(experimentId);
        }
        if (roster == null || roster.isEmpty()) {
            return new TeacherStudentExperimentResult(false, Collections.emptyList());
        }

        List<Experiment> experiments = loadLegacyExperiments(teacherId, classKeyword);

        // 当 experimentId 明确指定时，只保留该实验；若未从教师实验列表中查到，直接按 ID 加载
        if (experimentId != null) {
            if (experiments != null && !experiments.isEmpty()) {
                List<Experiment> filtered = experiments.stream()
                        .filter(e -> e != null && experimentId.equals(e.getExperiment_id()))
                        .collect(Collectors.toList());
                experiments = filtered.isEmpty() ? Collections.emptyList() : filtered;
            }
            // 如果实验列表里没有这个实验（或列表为空），尝试直接按 ID 加载
            if (experiments == null || experiments.isEmpty()) {
                Experiment singleExperiment = experimentService.findExperimentById(experimentId);
                experiments = singleExperiment == null
                        ? Collections.emptyList()
                        : Collections.singletonList(singleExperiment);
            }
        }

        if (experiments == null || experiments.isEmpty()) {
            return new TeacherStudentExperimentResult(true, Collections.emptyList());
        }

        List<Integer> experimentIds = experiments.stream()
                .filter(Objects::nonNull)
                .map(Experiment::getExperiment_id)
                .collect(Collectors.toList());

        Set<String> lookupKeys = new LinkedHashSet<>();
        Set<String> studentIds = new LinkedHashSet<>();
        for (TeacherStudentAssignmentRow student : roster) {
            if (hasText(student.getStudentUsername())) {
                lookupKeys.add(student.getStudentUsername());
            }
            if (hasText(student.getStudentId())) {
                lookupKeys.add(student.getStudentId());
                studentIds.add(student.getStudentId());
            }
        }

        Map<String, TeacherExperimentScoreRow> scoreByCompositeKey = lookupKeys.isEmpty()
                ? Collections.emptyMap()
                : teacherExperimentQueryDao.findPerExperimentSumScoresByUsernames(new ArrayList<>(lookupKeys))
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        row -> buildCompositeKey(row.getUsername(), row.getExperimentId()),
                        row -> row,
                        (left, right) -> preferLegacyScoreRow(left, right),
                        LinkedHashMap::new
                ));

        // 从 submit_situation 表查询，作为 score 表数据不足时的兜底数据源
        // submit_situation 是最完整的原始提交记录（3620条 vs score 的 1900条）
        Map<String, TeacherExperimentScoreRow> submitSituationByCompositeKey = lookupKeys.isEmpty()
                ? Collections.emptyMap()
                : teacherExperimentQueryDao.findPerExperimentSumScoresFromSubmitSituation(
                        new ArrayList<>(lookupKeys), experimentId)
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        row -> buildCompositeKey(row.getUsername(), row.getExperimentId()),
                        row -> row,
                        (left, right) -> preferLegacyScoreRow(left, right),
                        LinkedHashMap::new
                ));

        // 合并两个数据源：score 表优先，submit_situation 兜底补充缺失的 (student, experiment) 组合
        for (Map.Entry<String, TeacherExperimentScoreRow> entry : submitSituationByCompositeKey.entrySet()) {
            scoreByCompositeKey.putIfAbsent(entry.getKey(), entry.getValue());
        }

        Map<String, TeacherExperimentPlagiarismRow> plagiarismByCompositeKey =
                studentIds.isEmpty() || experimentIds.isEmpty()
                        ? Collections.emptyMap()
                        : teacherExperimentQueryDao.findPlagiarismRates(
                                new ArrayList<>(studentIds),
                                experimentIds
                        ).stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(
                                row -> buildCompositeKey(row.getStudentId(), row.getExperimentId()),
                                row -> row,
                                (left, right) -> left,
                                LinkedHashMap::new
                        ));

        List<Map<String, Object>> rows = new ArrayList<>(roster.size() * experiments.size());
        for (TeacherStudentAssignmentRow student : roster) {
            for (Experiment experiment : experiments) {
                TeacherExperimentScoreRow scoreRow = findLegacyScoreRow(scoreByCompositeKey, student, experiment.getExperiment_id());
                TeacherExperimentPlagiarismRow plagiarismRow = plagiarismByCompositeKey.get(
                        buildCompositeKey(student.getStudentId(), experiment.getExperiment_id())
                );

                Map<String, Object> experimentData = new LinkedHashMap<>();
                experimentData.put("studentId", student.getStudentId());
                experimentData.put("studentName", student.getStudentName());
                experimentData.put(
                        "studentUsername",
                        hasText(student.getStudentUsername()) ? student.getStudentUsername() : student.getStudentId()
                );
                experimentData.put("classId", student.getClassId());
                experimentData.put("className", student.getClassName());
                experimentData.put("experimentId", experiment.getExperiment_id());
                experimentData.put("experimentName", experiment.getName());
                experimentData.put("deadline", experiment.getDeadline());
                experimentData.put("status", mapLegacyStatus(scoreRow));
                experimentData.put("submitTime", scoreRow == null ? null : scoreRow.getSubmitTime());
                experimentData.put(
                        "score",
                        scoreRow == null || scoreRow.getScore() == null ? 0.0 : scoreRow.getScore().doubleValue()
                );
                experimentData.put(
                        "plagiarismRate",
                        roundTwoDecimals(calculateAveragePlagiarismRate(
                                plagiarismRow == null ? null : plagiarismRow.getPlagiarismRate()
                        ))
                );
                rows.add(experimentData);
            }
        }

        return new TeacherStudentExperimentResult(true, rows);
    }

    private List<Experiment> loadLegacyExperiments(Integer teacherId, String classKeyword) {
        if (teacherId == null) {
            return Collections.emptyList();
        }
        String teacherIdText = String.valueOf(teacherId);
        if (hasText(classKeyword)) {
            return experimentService.findExperimentsByClassKeyword(classKeyword, teacherIdText);
        }
        return experimentService.findExperimentsByTeacherId(teacherIdText);
    }

    private int getStudentCount(Integer teacherId) {
        Integer studentCount = teacherId == null ? null : studentDao.getStudentCountByTeacherId(teacherId);
        return studentCount == null ? 0 : studentCount;
    }

    private String normalizeClassKeyword(String classKeyword) {
        return hasText(classKeyword) ? classKeyword.replaceAll("[\\s\\u3000]+", "") : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String mapUnifiedStatus(String submissionStatus) {
        if (!hasText(submissionStatus) || "NOT_STARTED".equalsIgnoreCase(submissionStatus)) {
            return "not_started";
        }
        if ("GRADED".equalsIgnoreCase(submissionStatus) || "SUBMITTED".equalsIgnoreCase(submissionStatus)) {
            return "completed";
        }
        if ("IN_PROGRESS".equalsIgnoreCase(submissionStatus)) {
            return "in_progress";
        }
        return "not_started";
    }

    private String mapLegacyStatus(TeacherExperimentScoreRow scoreRow) {
        if (scoreRow == null) {
            return "not_started";
        }
        if ("completed".equalsIgnoreCase(scoreRow.getStatus())
                || scoreRow.getSubmitTime() != null
                || (scoreRow.getScore() != null && scoreRow.getScore() > 0)) {
            return "completed";
        }
        return "not_started";
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private String buildCompositeKey(String left, Integer right) {
        return (left == null ? "" : left) + "#" + (right == null ? "" : right);
    }

    private TeacherExperimentScoreRow findLegacyScoreRow(
            Map<String, TeacherExperimentScoreRow> scoreByCompositeKey,
            TeacherStudentAssignmentRow student,
            Integer experimentId) {
        if (student == null || experimentId == null) {
            return null;
        }
        if (!scoreByCompositeKey.isEmpty()) {
            TeacherExperimentScoreRow scoreRow = null;
            if (hasText(student.getStudentId())) {
                scoreRow = scoreByCompositeKey.get(buildCompositeKey(student.getStudentId(), experimentId));
            }
            if (scoreRow == null && hasText(student.getStudentUsername())) {
                scoreRow = scoreByCompositeKey.get(buildCompositeKey(student.getStudentUsername(), experimentId));
            }
            if (scoreRow != null) {
                return scoreRow;
            }
        }
        return null;
    }

    private TeacherExperimentScoreRow preferLegacyScoreRow(
            TeacherExperimentScoreRow left,
            TeacherExperimentScoreRow right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        if (right.getSubmitTime() != null && left.getSubmitTime() == null) {
            return right;
        }
        return left;
    }

    private double calculateAveragePlagiarismRate(String plagiarismRates) {
        if (!hasText(plagiarismRates)) {
            return 0.0;
        }

        String[] rates = plagiarismRates.split(",");
        double sum = 0.0;
        int count = 0;
        for (String rate : rates) {
            if (Objects.equals("-", rate.trim())) {
                continue;
            }
            try {
                sum += Double.parseDouble(rate.replace("%", "").trim());
                count++;
            } catch (NumberFormatException ignored) {
                // Ignore malformed fragments and keep valid percentages.
            }
        }
        return count > 0 ? sum / count : 0.0;
    }
}
