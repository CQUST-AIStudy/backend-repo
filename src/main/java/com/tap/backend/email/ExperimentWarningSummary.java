package com.tap.backend.email;

import java.util.List;

/**
 * 多实验预警汇总 —— 一封邮件包含多个触发预警的实验。
 */
public class ExperimentWarningSummary {

    private final int experimentId;
    private final String experimentName;
    private final List<ProblemWarningInfo> problems;

    public ExperimentWarningSummary(int experimentId, String experimentName, List<ProblemWarningInfo> problems) {
        this.experimentId = experimentId;
        this.experimentName = experimentName;
        this.problems = problems;
    }

    public int getExperimentId() { return experimentId; }

    public String getExperimentName() { return experimentName; }

    public List<ProblemWarningInfo> getProblems() { return problems; }
}
