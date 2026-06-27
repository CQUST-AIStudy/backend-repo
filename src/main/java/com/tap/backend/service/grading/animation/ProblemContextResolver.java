package com.tap.backend.service.grading.animation;

import com.tap.backend.academic.dao.ExperimentDao;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.domain.grading.GradingRubricEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.RubricDimensionEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 根据批改任务解析题目上下文，供错误演示动画使用。
 */
@Component
public class ProblemContextResolver {

    private static final Pattern TEST_CASE_PATTERN = Pattern.compile(
            "输入[:：]\\s*(.+?)\\s*输出[:：]\\s*(.+?)(?=输入[:：]|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ExperimentDao experimentDao;

    public ProblemContextResolver(ExperimentDao experimentDao) {
        this.experimentDao = experimentDao;
    }

    public ProblemContext resolve(GradingTaskEntity task) {
        if (task == null) {
            return emptyContext();
        }

        Long experimentId = task.getExperimentId();
        String title = null;
        String requirements = null;
        String description = null;
        if (experimentId != null) {
            try {
                Experiment experiment = experimentDao.findExperimentById(experimentId.intValue());
                if (experiment != null) {
                    title = firstNonBlank(experiment.getName(), experiment.getDescribe());
                    description = experiment.getDescribe();
                    requirements = experiment.getRequirements();
                }
            } catch (Exception ignored) {
                // 如果实验信息读取失败，继续用评分维度兜底
            }
        }

        StringBuilder reqBuilder = new StringBuilder();
        if (requirements != null && !requirements.isBlank()) {
            reqBuilder.append(requirements.trim());
        }
        if (description != null && !description.isBlank()) {
            if (!reqBuilder.isEmpty()) {
                reqBuilder.append("\n");
            }
            reqBuilder.append(description.trim());
        }

        // 追加评分维度描述作为实验要求摘要
        GradingRubricEntity rubric = task.getRubric();
        if (rubric != null && rubric.getDimensions() != null) {
            for (RubricDimensionEntity dimension : rubric.getDimensions()) {
                if (dimension.getName() != null && !dimension.getName().isBlank()) {
                    if (!reqBuilder.isEmpty()) {
                        reqBuilder.append("\n");
                    }
                    reqBuilder.append("- ").append(dimension.getName());
                    if (dimension.getDescription() != null && !dimension.getDescription().isBlank()) {
                        reqBuilder.append("：").append(dimension.getDescription());
                    }
                }
            }
        }

        List<Map<String, String>> testCases = parseTestCases(requirements);
        String expectedOutput = testCases.isEmpty() ? null : testCases.get(0).get("expectedOutput");

        return new ProblemContext(
                experimentId,
                title == null ? (rubric != null ? rubric.getName() : "") : title,
                reqBuilder.toString(),
                testCases,
                expectedOutput,
                null
        );
    }

    private List<Map<String, String>> parseTestCases(String requirements) {
        List<Map<String, String>> result = new ArrayList<>();
        if (requirements == null || requirements.isBlank()) {
            return result;
        }
        Matcher matcher = TEST_CASE_PATTERN.matcher(requirements);
        while (matcher.find()) {
            Map<String, String> testCase = new LinkedHashMap<>();
            testCase.put("input", matcher.group(1).trim().replaceAll("\\s+", " "));
            testCase.put("expectedOutput", matcher.group(2).trim().replaceAll("\\s+", " "));
            result.add(testCase);
        }
        return result;
    }

    private ProblemContext emptyContext() {
        return new ProblemContext(null, "", "", List.of(), null, null);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
