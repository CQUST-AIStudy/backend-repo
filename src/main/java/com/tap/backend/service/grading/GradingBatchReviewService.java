package com.tap.backend.service.grading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.ai.AiProvider;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.ScoreItemEntity;
import com.tap.backend.dto.grading.AgentConfigDto;
import com.tap.backend.dto.grading.BatchReviewDto;
import com.tap.backend.dto.grading.ScoreDistributionDto;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.repo.ScoreItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class GradingBatchReviewService {
    private static final Logger log = LoggerFactory.getLogger(GradingBatchReviewService.class);
    private static final String DEFAULT_CONFIG_CODE = "batch_review_default";

    private final GradingTaskRepository taskRepository;
    private final GradingSubmissionRepository submissionRepository;
    private final ScoreItemRepository scoreItemRepository;
    private final GradingAgentConfigService configService;
    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public GradingBatchReviewService(GradingTaskRepository taskRepository,
                                     GradingSubmissionRepository submissionRepository,
                                     ScoreItemRepository scoreItemRepository,
                                     GradingAgentConfigService configService,
                                     AiProvider aiProvider,
                                     ObjectMapper objectMapper,
                                     JdbcTemplate jdbcTemplate) {
        this.taskRepository = taskRepository;
        this.submissionRepository = submissionRepository;
        this.scoreItemRepository = scoreItemRepository;
        this.configService = configService;
        this.aiProvider = aiProvider;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public BatchReviewDto getReview(Long taskId) {
        GradingTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (task.getBatchReviewStatus() == GradingTaskEntity.BatchReviewStatus.GENERATING) {
            return new BatchReviewDto(GradingTaskEntity.BatchReviewStatus.GENERATING.name(),
                    null, null, null, null, buildDistributionForTask(taskId), null, null);
        }
        if (task.getBatchReviewStatus() == GradingTaskEntity.BatchReviewStatus.FAILED) {
            return task.getBatchReviewJson() == null
                    ? failedResult("批次总评生成失败，后端未记录具体原因")
                    : parseStored(task.getBatchReviewJson(), task.getBatchReviewStatus());
        }
        if (task.getBatchReviewStatus() == GradingTaskEntity.BatchReviewStatus.PENDING
                || task.getBatchReviewJson() == null) {
            return BatchReviewDto.pending();
        }
        return parseStored(task.getBatchReviewJson(), task.getBatchReviewStatus());
    }

    @Transactional
    public void triggerGeneration(Long taskId) {
        GradingTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        if (task.getBatchReviewStatus() == GradingTaskEntity.BatchReviewStatus.GENERATING) {
            return;
        }
        task.setBatchReviewStatus(GradingTaskEntity.BatchReviewStatus.GENERATING);
        task.setBatchReviewJson(null);
        taskRepository.save(task);
        generateAsync(taskId);
    }

    @Async("aiExecutor")
    public void generateAsync(Long taskId) {
        try {
            internalGenerate(taskId);
        } catch (Exception e) {
            log.error("Batch review generation failed for task {}", taskId, e);
            markFailed(taskId, rootCauseMessage(e));
        }
    }

    @Transactional
    protected void internalGenerate(Long taskId) {
        GradingTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        AgentConfigDto config = resolveConfig(task);
        if (!config.enabled()) {
            throw new IllegalStateException("Batch review config is disabled");
        }

        List<GradingSubmissionEntity> submissions = submissionRepository.findAllByTaskId(taskId);
        if (submissions.isEmpty()) {
            saveResult(task, emptyResult());
            return;
        }

        String prompt = buildPrompt(task, submissions, config.promptTemplate());
        String raw;
        try {
            raw = aiProvider.chat(prompt, config.model());
        } catch (Exception e) {
            throw new RuntimeException("AI call failed", e);
        }

        Map<String, Object> parsed = parseAiJson(raw);
        BatchReviewDto dto = normalizeResult(parsed, submissions);
        saveResult(task, dto);
    }

    private AgentConfigDto resolveConfig(GradingTaskEntity task) {
        if (task.getBatchReviewPrompt() != null && !task.getBatchReviewPrompt().isBlank()) {
            String model = firstNonBlank(task.getBatchReviewModel(), aiProvider.model());
            return new AgentConfigDto(null, null, "task-override",
                    task.getBatchReviewPrompt(), model,
                    new BigDecimal("0.30"), 1600, true);
        }
        AgentConfigDto configured = configService.findByCode(DEFAULT_CONFIG_CODE)
                .orElseGet(this::fallbackConfig);
        return new AgentConfigDto(
                configured.id(), configured.code(), configured.name(), configured.promptTemplate(),
                aiProvider.model(), configured.temperature(), configured.maxTokens(), configured.enabled());
    }

    private AgentConfigDto fallbackConfig() {
        return new AgentConfigDto(null, DEFAULT_CONFIG_CODE, "default",
                "你是一位实验课主讲教师。请根据下面的真实学生成绩和分项得分，详细分析本次作业的学生表现、共性问题及教师可执行的教学建议。\n" +
                "必须严格返回 JSON：{\"summary\":\"...\",\"commonIssues\":[\"...\"],\"strengths\":[\"...\"],\"teachingAdvice\":\"...\"}\n",
                aiProvider.model(), new BigDecimal("0.30"), 1600, true);
    }

    private String buildPrompt(GradingTaskEntity task, List<GradingSubmissionEntity> submissions, String template) {
        String experimentName = resolveExperimentName(task);
        StringBuilder sb = new StringBuilder();
        sb.append("实验名称：").append(experimentName).append("\n");
        sb.append("评分标准：").append(task.getRubric() != null ? task.getRubric().getName() : "未命名评分标准").append("\n");
        if (task.getRubric() != null && task.getRubric().getCustomPrompt() != null) {
            sb.append("任务要求：").append(task.getRubric().getCustomPrompt()).append("\n");
        }
        sb.append("满分：").append(task.getScoreRangeMax() != null ? task.getScoreRangeMax() : "100").append("\n\n");
        sb.append("学生成绩明细（共 ").append(submissions.size()).append(" 人）：\n");
        for (GradingSubmissionEntity s : submissions) {
            sb.append("- ").append(s.getStudentName()).append("：").append(formatScore(s.getTotalScore())).append("/").append(task.getScoreRangeMax() != null ? task.getScoreRangeMax() : "100");
            if (s.getFinalReviewComment() != null && !s.getFinalReviewComment().isBlank()) {
                sb.append("，评语：").append(truncate(s.getFinalReviewComment(), 240));
            }
            List<ScoreItemEntity> items = scoreItemRepository.findAllBySubmissionId(s.getId());
            if (!items.isEmpty()) {
                sb.append("，分项：");
                for (ScoreItemEntity item : items) {
                    String dimName = item.getDimension() != null ? item.getDimension().getName() : "维度" + item.getDimensionId();
                    sb.append(dimName).append("(").append(formatScore(item.getScore())).append("/").append(item.getMaxScore()).append(") ");
                }
            }
            sb.append("\n");
        }
        sb.append("\n得分分布：\n").append(renderDistribution(submissions)).append("\n");

        String rendered = template
                .replace("{{task}}", task.getRubric() != null ? task.getRubric().getName() : "")
                .replace("{{experimentName}}", experimentName)
                .replace("{{maxScore}}", String.valueOf(task.getScoreRangeMax() != null ? task.getScoreRangeMax() : 100))
                .replace("{{studentCount}}", String.valueOf(submissions.size()))
                .replace("{{submissionsSummary}}", sb.toString())
                .replace("{{dimensions}}", renderDimensions(task));
        if (!template.contains("{{submissionsSummary}}")) {
            rendered += "\n\n# 本次作业真实数据\n" + sb;
        }
        return rendered + "\n\n分析要求：结论必须引用上述人数、分数或分项证据；明确说明学生整体情况、至少2项共性问题，并给教师3至5条可直接执行的教学建议。不得猜测未提供的学校信息。";
    }

    private String resolveExperimentName(GradingTaskEntity task) {
        if (task.getAssignmentOfferingId() != null) {
            List<String> names = jdbcTemplate.query(
                    "SELECT COALESCE(NULLIF(TRIM(ao.title_override), ''), at.title) " +
                            "FROM assignment_offering ao JOIN assignment_template at ON at.id = ao.template_id " +
                            "WHERE ao.id = ? LIMIT 1",
                    (rs, rowNum) -> rs.getString(1), task.getAssignmentOfferingId());
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) return names.get(0).trim();
        }
        if (task.getExperimentId() != null) {
            List<String> names = jdbcTemplate.query(
                    "SELECT experiment_name FROM experiment WHERE experiment_id = ? LIMIT 1",
                    (rs, rowNum) -> rs.getString(1), task.getExperimentId());
            if (!names.isEmpty() && names.get(0) != null && !names.get(0).isBlank()) return names.get(0).trim();
        }
        return "未关联具体实验";
    }

    private BatchReviewDto normalizeResult(Map<String, Object> parsed, List<GradingSubmissionEntity> submissions) {
        String summary = stringValue(parsed.get("summary"));
        List<String> commonIssues = extractStringList(parsed.get("commonIssues"));
        List<String> strengths = extractStringList(parsed.get("strengths"));
        String teachingAdviceRaw = stringValue(parsed.get("teachingAdvice"));
        List<String> teachingAdvice = teachingAdviceRaw.isBlank() ? List.of() : List.of(teachingAdviceRaw);

        return new BatchReviewDto(
                GradingTaskEntity.BatchReviewStatus.COMPLETED.name(),
                summary.isEmpty() ? "已完成批次总评" : summary,
                commonIssues,
                strengths,
                teachingAdvice,
                buildDistribution(submissions),
                null,
                Instant.now()
        );
    }

    private BatchReviewDto parseStored(String json, GradingTaskEntity.BatchReviewStatus status) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<String> commonIssues = (List<String>) parsed.get("commonIssues");
            @SuppressWarnings("unchecked")
            List<String> strengths = (List<String>) parsed.get("strengths");
            String teachingAdviceRaw = stringValue(parsed.get("teachingAdvice"));
            List<String> teachingAdvice = teachingAdviceRaw.isBlank()
                    ? ((parsed.get("teachingAdvice") instanceof List<?> l) ? (List<String>) (List<?>) l : List.of())
                    : List.of(teachingAdviceRaw);
            return new BatchReviewDto(
                    status.name(),
                    stringValue(parsed.get("summary")),
                    commonIssues != null ? commonIssues : List.of(),
                    strengths != null ? strengths : List.of(),
                    teachingAdvice,
                    parseDistribution(parsed.get("scoreDistribution")),
                    stringValue(parsed.get("errorMessage")),
                    parseInstant(parsed.get("generatedAt"))
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse stored batch review", e);
            return new BatchReviewDto(status.name(), null, List.of(), List.of(), List.of(), null,
                    "批次总评结果解析失败: " + e.getOriginalMessage(), null);
        }
    }

    private void saveResult(GradingTaskEntity task, BatchReviewDto dto) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("summary", dto.summary());
            map.put("commonIssues", dto.commonIssues());
            map.put("strengths", dto.strengths());
            map.put("teachingAdvice", dto.teachingAdvice());
            map.put("scoreDistribution", dto.scoreDistribution());
            map.put("generatedAt", dto.generatedAt() == null ? null : dto.generatedAt().toString());
            task.setBatchReviewJson(objectMapper.writeValueAsString(map));
            task.setBatchReviewStatus(GradingTaskEntity.BatchReviewStatus.COMPLETED);
            taskRepository.save(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize batch review", e);
        }
    }

    private void markFailed(Long taskId, String errorMessage) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setBatchReviewStatus(GradingTaskEntity.BatchReviewStatus.FAILED);
            try {
                task.setBatchReviewJson(objectMapper.writeValueAsString(Map.of(
                        "errorMessage", errorMessage,
                        "generatedAt", Instant.now().toString())));
            } catch (JsonProcessingException serializationError) {
                log.error("Failed to store batch review error for task {}", taskId, serializationError);
                task.setBatchReviewJson("{\"errorMessage\":\"批次总评生成失败，错误原因序列化失败\"}");
            }
            taskRepository.save(task);
        });
    }

    private BatchReviewDto emptyResult() {
        return new BatchReviewDto(GradingTaskEntity.BatchReviewStatus.COMPLETED.name(),
                "暂无可分析的学生数据", List.of(), List.of(), List.of(), null, null, Instant.now());
    }

    private BatchReviewDto failedResult(String errorMessage) {
        return new BatchReviewDto(GradingTaskEntity.BatchReviewStatus.FAILED.name(),
                null, List.of(), List.of(), List.of(), null, errorMessage, null);
    }

    private ScoreDistributionDto buildDistributionForTask(Long taskId) {
        return buildDistribution(submissionRepository.findAllByTaskId(taskId));
    }

    private Instant parseInstant(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() > 800 ? message.substring(0, 800) : message;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : (fallback == null ? "" : fallback);
    }

    private Map<String, Object> parseAiJson(String raw) {
        String cleaned = raw;
        if (cleaned.contains("```")) {
            cleaned = cleaned.replaceAll("(?s)```(json)?\\s*", "").replace("```", "").trim();
        }
        try {
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("AI did not return valid JSON, wrapping raw text");
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("summary", cleaned.trim());
            return map;
        }
    }

    private ScoreDistributionDto buildDistribution(List<GradingSubmissionEntity> submissions) {
        List<Integer> scores = submissions.stream()
                .map(s -> toIntScore(s.getTotalScore()))
                .sorted()
                .toList();
        int count = scores.size();
        if (count == 0) return null;
        int sum = scores.stream().mapToInt(Integer::intValue).sum();
        BigDecimal avg = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        int median = count % 2 == 1 ? scores.get(count / 2)
                : (scores.get(count / 2 - 1) + scores.get(count / 2)) / 2;

        int step = 10;
        int maxScore = Math.max(scores.get(count - 1), 100);
        List<ScoreDistributionDto.ScoreBinDto> bins = new ArrayList<>();
        for (int min = 0; min < maxScore; min += step) {
            int maxEx = Math.min(min + step, maxScore + 1);
            final int lo = min, hi = maxEx;
            int c = (int) scores.stream().filter(s -> s >= lo && s < hi).count();
            bins.add(new ScoreDistributionDto.ScoreBinDto(lo, hi, c));
        }
        return new ScoreDistributionDto(bins, avg, scores.get(count - 1), scores.get(0), median, count);
    }

    private ScoreDistributionDto parseDistribution(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.convertValue(obj, ScoreDistributionDto.class);
        } catch (Exception e) {
            log.warn("Failed to parse scoreDistribution", e);
            return null;
        }
    }

    private String renderDistribution(List<GradingSubmissionEntity> submissions) {
        ScoreDistributionDto dist = buildDistribution(submissions);
        if (dist == null) return "无数据";
        StringBuilder sb = new StringBuilder();
        sb.append("平均分：").append(dist.average()).append("，中位数：").append(dist.median())
          .append("，最高分：").append(dist.highest()).append("，最低分：").append(dist.lowest()).append("\n");
        for (ScoreDistributionDto.ScoreBinDto b : dist.bins()) {
            sb.append(b.minInclusive()).append("-").append(b.maxExclusive() - 1).append("分：")
              .append(b.count()).append("人\n");
        }
        return sb.toString();
    }

    private String renderDimensions(GradingTaskEntity task) {
        if (task.getRubric() == null || task.getRubric().getDimensions() == null) {
            return "未配置评分维度";
        }
        StringBuilder sb = new StringBuilder();
        for (var dim : task.getRubric().getDimensions()) {
            sb.append("- ").append(dim.getName()).append("（满分").append(dim.getMaxScore()).append("，权重").append(dim.getWeight()).append("）\n");
        }
        return sb.toString();
    }

    private String stringValue(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        if (o instanceof Map<?, ?> m) {
            Object fallback = m.get("summary");
            if (fallback == null) fallback = m.toString();
            Object value = m.get("issue");
            if (value == null) value = fallback;
            return String.valueOf(value);
        }
        return String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractStringList(Object o) {
        if (o instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String issue = valueOf(m.get("issue"));
                    String suggestion = valueOf(m.get("suggestion"));
                    String affected = valueOf(m.get("affectedRatio"));
                    String line = (issue.isBlank() ? "" : issue)
                            + (affected.isBlank() ? "" : "（" + affected + "）")
                            + (suggestion.isBlank() ? "" : "；建议：" + suggestion);
                    result.add(line.isBlank() ? m.toString() : line);
                } else {
                    result.add(String.valueOf(item));
                }
            }
            return result.stream().filter(s -> !s.isBlank()).toList();
        }
        if (o instanceof String s && !s.isBlank()) return List.of(s);
        return List.of();
    }

    private String valueOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String clean = s.replaceAll("\\s+", " ").trim();
        return clean.length() > max ? clean.substring(0, max) + "..." : clean;
    }

    private int toIntScore(BigDecimal score) {
        if (score == null) return 0;
        return score.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private String formatScore(BigDecimal score) {
        if (score == null) return "-";
        return score.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
