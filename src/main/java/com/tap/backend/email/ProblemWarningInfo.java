package com.tap.backend.email;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个题目的预警信息，用于邮件汇总。
 */
public class ProblemWarningInfo {

    private final Long problemId;
    private final String problemTitle;
    private final int totalSubmissions;
    private final int acceptedCount;
    /** 所有判题状态 → 次数（如 COMPILE_ERROR→5, WRONG_ANSWER→3） */
    private final Map<String, Integer> statusCounts;
    private final String level;
    private final String warningType;
    private final List<String> suggestedActions;

    public ProblemWarningInfo(Long problemId, String problemTitle,
                              int totalSubmissions, int acceptedCount,
                              Map<String, Integer> statusCounts,
                              String level, String warningType,
                              List<String> suggestedActions) {
        this.problemId = problemId;
        this.problemTitle = problemTitle;
        this.totalSubmissions = totalSubmissions;
        this.acceptedCount = acceptedCount;
        this.statusCounts = statusCounts != null ? statusCounts : new LinkedHashMap<>();
        this.level = level;
        this.warningType = warningType;
        this.suggestedActions = suggestedActions;
    }

    public Long getProblemId() { return problemId; }
    public String getProblemTitle() { return problemTitle; }
    public int getTotalSubmissions() { return totalSubmissions; }
    public int getAcceptedCount() { return acceptedCount; }
    public Map<String, Integer> getStatusCounts() { return statusCounts; }
    public String getLevel() { return level; }
    public String getWarningType() { return warningType; }
    public List<String> getSuggestedActions() { return suggestedActions; }

    /** 错误状态中文名（用于邮件展示） */
    public static String statusLabel(String status) {
        if (status == null) return "未知";
        switch (status.toUpperCase()) {
            case "COMPILE_ERROR":       return "编译错误";
            case "WRONG_ANSWER":        return "答案错误";
            case "PARTIAL_ACCEPTED":    return "部分正确";
            case "RUNTIME_ERROR":       return "运行时错误";
            case "TIME_LIMIT_EXCEEDED": return "超时";
            case "SEGMENTATION_FAULT":  return "段错误";
            case "MULTIPLE_ERROR":      return "多次错误";
            case "MEMORY_LIMIT_EXCEEDED": return "内存超限";
            default:                    return status;
        }
    }
}
