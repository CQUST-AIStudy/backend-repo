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
    public TeacherExperimentListResult getTeacherExperimentList(Integer teacherId, Long classId) {
        if (!unifiedExperimentQueriesEnabled) {
            return getLegacyTeacherExperimentList(teacherId, classId);
        }

        List<TeacherExperimentSummaryRow> summaries = teacherExperimentQueryDao
                .findTeacherExperimentSummaries(teacherId, classId);
        if (summaries == null || summaries.isEmpty()) {
            return new TeacherExperimentListResult(Collections.emptyList(), 0);
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
    public TeacherStudentExperimentResult getAllStudentExperiments(Integer teacherId, Long classId) {
        if (!unifiedExperimentQueriesEnabled) {
            return getLegacyAllStudentExperiments(teacherId, classId);
        }

        List<TeacherStudentAssignmentRow> assignments = teacherExperimentQueryDao
                .findTeacherStudentAssignments(teacherId, classId);
        if (assignments == null || assignments.isEmpty()) {
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

    private TeacherExperimentListResult getLegacyTeacherExperimentList(Integer teacherId, Long classId) {
        List<TeacherStudentAssignmentRow> roster = teacherExperimentQueryDao.findTeacherStudentRoster(teacherId, classId);
        int studentCount = roster == null ? 0 : roster.size();

        List<Experiment> experiments = teacherId == null
                ? Collections.emptyList()
                : experimentService.findExperimentsByTeacherId(String.valueOf(teacherId));
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

    private TeacherStudentExperimentResult getLegacyAllStudentExperiments(Integer teacherId, Long classId) {
        List<TeacherStudentAssignmentRow> roster = teacherExperimentQueryDao.findTeacherStudentRoster(teacherId, classId);
        if (roster == null || roster.isEmpty()) {
            return new TeacherStudentExperimentResult(false, Collections.emptyList());
        }

        List<Experiment> experiments = teacherId == null
                ? Collections.emptyList()
                : experimentService.findExperimentsByTeacherId(String.valueOf(teacherId));
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

    private int getStudentCount(Integer teacherId) {
        Integer studentCount = teacherId == null ? null : studentDao.getStudentCountByTeacherId(teacherId);
        return studentCount == null ? 0 : studentCount;
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
        if (student == null || experimentId == null || scoreByCompositeKey.isEmpty()) {
            return null;
        }
        TeacherExperimentScoreRow scoreRow = null;
        if (hasText(student.getStudentId())) {
            scoreRow = scoreByCompositeKey.get(buildCompositeKey(student.getStudentId(), experimentId));
        }
        if (scoreRow == null && hasText(student.getStudentUsername())) {
            scoreRow = scoreByCompositeKey.get(buildCompositeKey(student.getStudentUsername(), experimentId));
        }
        return scoreRow;
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
