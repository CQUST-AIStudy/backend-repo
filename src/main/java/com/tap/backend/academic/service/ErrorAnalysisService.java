package com.tap.backend.academic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.academic.dao.AiErrorAnalysisReportDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.AiErrorAnalysisReport;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.StudentSubmissionAttempt;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 错误分析服务
 *
 * 提供两种调用方式：
 * 1. 同步透传：proxyToMicroservice(path, payload) → 直接转发给 Python 微服务
 * 2. 异步管线：triggerAnalysisPipeline(studentNo, studentName, experimentId)
 *    → 后台线程查询 DB → 调用 AI → 存入 MySQL + Redis → 前端轮询/主动获取
 */
@Service
public class ErrorAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorAnalysisService.class);
    private static final String REDIS_KEY_PREFIX = "ai:analysis:";
    private static final long REDIS_TTL_HOURS = 24;
    private static final SimpleDateFormat ISO_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private TeacherExperimentQueryDao teacherExperimentQueryDao;

    @Autowired
    private AiErrorAnalysisReportDao reportDao;

    @Autowired
    private ExperimentService experimentService;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${tap.error-analysis.base-url:http://127.0.0.1:8002}")
    private String errorAnalysisBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 异步管线（核心） ====================

    /**
     * 异步触发完整 AI 分析管线：
     * 查 DB → 调 Python → 调 DeepSeek → 存 MySQL + Redis
     *
     * 在提交代码判题后调用此方法（不阻塞主线程）
     */
    @Async("aiExecutor")
    public void triggerAnalysisPipeline(String studentNo, String studentName, int experimentId) {
        logger.info("AI analysis pipeline started: student={}, experiment={}", studentNo, experimentId);
        long start = System.currentTimeMillis();

        try {
            // 1. 查数据库：获取该学生所有提交尝试
            List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                    .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);

            if (attempts == null || attempts.isEmpty()) {
                logger.info("AI pipeline skipped: no submission attempts for student={}, experiment={}",
                        studentNo, experimentId);
                return;
            }

            Experiment experiment = experimentService.findExperimentById(experimentId);
            String experimentName = experiment != null ? experiment.getName() : ("实验" + experimentId);

            // 2. 构建提交历史
            List<Map<String, Object>> submissions = buildSubmissionList(attempts);
            int totalErrors = countErrors(attempts);

            // 3. 删除旧报告（同一学生+实验），准备写入新报告
            reportDao.deleteByStudentAndExperiment(studentNo, experimentId);

            // 4. 并行调用 3 个 AI 接口
            // 4a. 错误分析
            Map<String, Object> errorPayload = buildErrorPayload(studentNo, studentName,
                    experimentId, experimentName, submissions, attempts);
            Map<String, Object> errorResult = callMicroservice("/analyze/error", errorPayload);
            saveReport(studentNo, experimentId, experimentName, "ERROR", errorResult);

            // 4b. 学习建议
            Map<String, Object> learningPayload = buildLearningPayload(studentNo, studentName, attempts);
            if (hasErrors(learningPayload)) {
                Map<String, Object> learningResult = callMicroservice("/analyze/learning", learningPayload);
                saveReport(studentNo, experimentId, experimentName, "LEARNING", learningResult);
            }

            // 4c. 干预预警（仅在错误次数 >= 3 时触发）
            if (totalErrors >= 3) {
                Map<String, Object> warningPayload = buildWarningPayload(studentNo, studentName,
                        experimentId, experimentName, attempts);
                Map<String, Object> warningResult = callMicroservice("/analyze/warning", warningPayload);
                saveReport(studentNo, experimentId, experimentName, "WARNING", warningResult);
            }

            long elapsed = System.currentTimeMillis() - start;
            logger.info("AI analysis pipeline completed: student={}, experiment={}, elapsed={}ms",
                    studentNo, experimentId, elapsed);

        } catch (Exception e) {
            logger.error("AI analysis pipeline failed: student={}, experiment={}",
                    studentNo, experimentId, e);
        }
    }

    // ==================== 同步透传 ====================

    public Map<String, Object> proxyToMicroservice(String path, Map<String, Object> payload) {
        return callMicroservice(path, payload);
    }

    // ==================== 数据库查询方法 ====================

    /**
     * 同步：从 DB 构建 payload → 调用微服务 → 返回（不存储）
     */
    public Map<String, Object> analyzeErrorFromDb(String studentNo, String studentName, int experimentId) {
        List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        Experiment experiment = experimentService.findExperimentById(experimentId);
        List<Map<String, Object>> submissions = buildSubmissionList(attempts);

        Map<String, Object> payload = buildErrorPayload(studentNo, studentName,
                experimentId, experiment != null ? experiment.getName() : ("实验" + experimentId), submissions, attempts);
        return callMicroservice("/analyze/error", payload);
    }

    public Map<String, Object> learningSuggestFromDb(String studentNo, String studentName, int experimentId) {
        List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        Map<String, Object> payload = buildLearningPayload(studentNo, studentName, attempts);
        if (!hasErrors(payload)) {
            logger.info("Learning suggest skipped: no errors for student={}, experiment={}", studentNo, experimentId);
            return null;
        }
        return callMicroservice("/analyze/learning", payload);
    }

    public Map<String, Object> warningAnalyzeFromDb(String studentNo, String studentName, int experimentId) {
        List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        return callMicroservice("/analyze/warning",
                buildWarningPayload(studentNo, studentName, experimentId,
                        experimentService.findExperimentById(experimentId) != null
                                ? experimentService.findExperimentById(experimentId).getName() : "",
                        attempts));
    }

    // ==================== 查询已存储的报告 ====================

    /**
     * 获取学生在指定实验的所有 AI 分析报告
     */
    public List<AiErrorAnalysisReport> getStoredReports(String studentNo, int experimentId) {
        // 先查 Redis 缓存
        List<AiErrorAnalysisReport> cached = getFromCache(studentNo, experimentId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        // 缓存未命中 → 查 DB
        List<AiErrorAnalysisReport> reports = reportDao.findByStudentAndExperiment(studentNo, experimentId);
        if (reports != null && !reports.isEmpty()) {
            putToCache(studentNo, experimentId, reports);
        }
        return reports != null ? reports : new ArrayList<>();
    }

    /**
     * 获取已存储报告并转为前端友好格式
     */
    public Map<String, Object> getStoredReportsAsMap(String studentNo, int experimentId) {
        List<AiErrorAnalysisReport> reports = getStoredReports(studentNo, experimentId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentNo", studentNo);
        result.put("experimentId", experimentId);
        result.put("totalReports", reports.size());

        for (AiErrorAnalysisReport r : reports) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("analysisId", r.getAnalysisId());
            data.put("reportType", r.getReportType());
            data.put("severity", r.getSeverity());
            data.put("aiGenerated", r.getAiGenerated());
            data.put("createdAt", r.getCreatedAt() != null ? ISO_FORMAT.format(r.getCreatedAt()) : null);

            // 反序列化 JSON 字段
            if (r.getOverallAssessment() != null) data.put("overallAssessment", r.getOverallAssessment());
            if (r.getSummaryMessage() != null) data.put("summaryMessage", r.getSummaryMessage());
            if (r.getWarningType() != null) data.put("warningType", r.getWarningType());
            if (r.getWarningMessage() != null) data.put("warningMessage", r.getWarningMessage());
            if (r.getTeacherNote() != null) data.put("teacherNote", r.getTeacherNote());
            if (r.getInterventionTriggered() != null) data.put("interventionTriggered", r.getInterventionTriggered());

            data.put("errorCategories", parseJsonArray(r.getErrorCategoriesJson()));
            data.put("learningSuggestions", parseJsonArray(r.getLearningSuggestionsJson()));
            data.put("weakPoints", parseJsonArray(r.getWeakPointsJson()));
            data.put("studyPlan", parseJsonArray(r.getStudyPlanJson()));
            data.put("recommendedProblems", parseJsonArray(r.getRecommendedProblemsJson()));

            result.put(r.getReportType().toLowerCase(), data);
        }
        return result;
    }

    // ==================== 私有方法 ====================

    private List<Map<String, Object>> buildSubmissionList(List<StudentSubmissionAttempt> attempts) {
        List<Map<String, Object>> submissions = new ArrayList<>();
        if (attempts == null) return submissions;
        for (int i = 0; i < attempts.size(); i++) {
            StudentSubmissionAttempt a = attempts.get(i);
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("attemptNo", i + 1);
            sub.put("judgeStatus", a.getJudgeStatus() != null ? a.getJudgeStatus() : "UNKNOWN");
            sub.put("compiler", a.getCompiler() != null ? a.getCompiler() : "");
            sub.put("errorMessage", a.getErrorMessage() != null ? a.getErrorMessage() : "");
            sub.put("code", a.getCode() != null ? a.getCode() : "");
            sub.put("problemTitle", a.getProblemTitle() != null ? a.getProblemTitle() : "");
            if (a.getSubmittedAt() != null) {
                sub.put("submittedAt", ISO_FORMAT.format(a.getSubmittedAt()));
            }
            submissions.add(sub);
        }
        return submissions;
    }

    private int countErrors(List<StudentSubmissionAttempt> attempts) {
        if (attempts == null) return 0;
        return (int) attempts.stream()
                .filter(a -> a.getJudgeStatus() != null && !"ACCEPTED".equalsIgnoreCase(a.getJudgeStatus()))
                .count();
    }

    private Map<String, Object> buildErrorPayload(String studentNo, String studentName,
                                                   int experimentId, String experimentName,
                                                   List<Map<String, Object>> submissions,
                                                   List<StudentSubmissionAttempt> attempts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", studentNo);
        payload.put("studentName", (studentName != null && !studentName.isBlank()) ? studentName : studentNo);
        payload.put("experimentId", experimentId);
        payload.put("experimentName", experimentName);
        // 从第一条 attempt 提取题目标题
        String problemTitle = "编程练习";
        if (attempts != null && !attempts.isEmpty()) {
            String pt = attempts.get(0).getProblemTitle();
            if (pt != null && !pt.isBlank()) problemTitle = pt;
        }
        payload.put("problemTitle", problemTitle);
        payload.put("problemDescription", "");
        payload.put("submissions", submissions != null ? submissions : new ArrayList<>());
        return payload;
    }

    private Map<String, Object> buildLearningPayload(String studentNo, String studentName,
                                                      List<StudentSubmissionAttempt> attempts) {
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        for (StudentSubmissionAttempt a : attempts) {
            String status = a.getJudgeStatus();
            if (status != null && !"ACCEPTED".equalsIgnoreCase(status)) {
                errorCounts.merge(status, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> errorHistory = new ArrayList<>();
        for (Map.Entry<String, Integer> e : errorCounts.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("errorType", e.getKey());
            item.put("count", e.getValue());
            errorHistory.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", studentNo);
        payload.put("studentName", (studentName != null && !studentName.isBlank()) ? studentName : studentNo);
        payload.put("errorHistory", errorHistory);
        payload.put("skillStates", new ArrayList<>());
        payload.put("previousRemark", "");
        return payload;
    }

    private Map<String, Object> buildWarningPayload(String studentNo, String studentName,
                                                     int experimentId, String experimentName,
                                                     List<StudentSubmissionAttempt> attempts) {
        int total = attempts.size();
        int accepted = 0, compileErr = 0, runtimeErr = 0, wrongAns = 0, tle = 0;
        String lastAt = "";
        for (StudentSubmissionAttempt a : attempts) {
            String s = a.getJudgeStatus();
            if (s == null) continue;
            switch (s.toUpperCase()) {
                case "ACCEPTED": accepted++; break;
                case "COMPILE_ERROR": compileErr++; break;
                case "RUNTIME_ERROR": runtimeErr++; break;
                case "WRONG_ANSWER": wrongAns++; break;
                case "TIME_LIMIT_EXCEEDED": tle++; break;
            }
        }
        if (!attempts.isEmpty() && attempts.get(attempts.size() - 1).getSubmittedAt() != null) {
            lastAt = ISO_FORMAT.format(attempts.get(attempts.size() - 1).getSubmittedAt());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("studentId", studentNo);
        payload.put("studentName", (studentName != null && !studentName.isBlank()) ? studentName : studentNo);
        payload.put("experimentId", experimentId);
        payload.put("experimentName", experimentName);
        payload.put("deadline", "");
        payload.put("totalSubmissions", total);
        payload.put("acceptedCount", accepted);
        payload.put("totalProblems", 1);
        payload.put("compileErrors", compileErr);
        payload.put("runtimeErrors", runtimeErr);
        payload.put("wrongAnswers", wrongAns);
        payload.put("timeLimitExceeded", tle);
        payload.put("lastSubmissionAt", lastAt);
        return payload;
    }

    // ==================== 存储 ====================

    private void saveReport(String studentNo, int experimentId, String experimentName,
                            String reportType, Map<String, Object> responseBody) {
        if (responseBody == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) responseBody.getOrDefault("data", responseBody);
        if (data == null || data.isEmpty()) return;

        AiErrorAnalysisReport report = new AiErrorAnalysisReport();
        report.setAnalysisId(safeString(data.get("analysisId"),
                reportType.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8)));
        report.setStudentNo(studentNo);
        report.setExperimentId(experimentId);
        report.setExperimentName(experimentName);
        report.setReportType(reportType);
        report.setSeverity(safeString(data.get("severity"), "MEDIUM"));
        report.setAiGenerated(Boolean.TRUE.equals(data.get("aiGenerated")));
        report.setOverallAssessment(safeString(data.get("overallAssessment"), null));
        report.setSummaryMessage(safeString(data.get("summaryMessage"), null));
        report.setWarningType(safeString(data.get("warningType"), null));
        report.setWarningMessage(safeString(data.get("warningMessage"), null));
        report.setTeacherNote(safeString(data.get("teacherNote"), null));
        report.setInterventionTriggered(Boolean.TRUE.equals(data.get("interventionTriggered")));

        report.setErrorCategoriesJson(toJson(data.get("errorCategories")));
        report.setLearningSuggestionsJson(toJson(data.get("learningSuggestions")));
        report.setWeakPointsJson(toJson(data.get("weakPoints")));
        report.setStudyPlanJson(toJson(data.get("studyPlan")));
        report.setRecommendedProblemsJson(toJson(data.get("recommendedProblems")));
        report.setRawResponseJson(toJson(responseBody));

        reportDao.save(report);
        logger.info("AI report saved: analysisId={}, type={}, student={}, experiment={}",
                report.getAnalysisId(), reportType, studentNo, experimentId);

        // 清空 Redis 缓存（下次查询时会重新加载）
        evictCache(studentNo, experimentId);
    }

    // ==================== Redis 缓存 ====================

    private String cacheKey(String studentNo, int experimentId) {
        return REDIS_KEY_PREFIX + studentNo + ":" + experimentId;
    }

    @SuppressWarnings("unchecked")
    private List<AiErrorAnalysisReport> getFromCache(String studentNo, int experimentId) {
        if (redisTemplate == null) return null;
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(studentNo, experimentId));
            if (json == null || json.isEmpty()) return null;
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, AiErrorAnalysisReport.class));
        } catch (Exception e) {
            logger.debug("Redis cache miss for {}/{}: {}", studentNo, experimentId, e.getMessage());
            return null;
        }
    }

    private void putToCache(String studentNo, int experimentId, List<AiErrorAnalysisReport> reports) {
        if (redisTemplate == null || reports == null || reports.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(reports);
            redisTemplate.opsForValue().set(cacheKey(studentNo, experimentId), json,
                    REDIS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.warn("Redis cache write failed: {}", e.getMessage());
        }
    }

    private void evictCache(String studentNo, int experimentId) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.delete(cacheKey(studentNo, experimentId));
        } catch (Exception ignored) {}
    }

    // ==================== 工具方法 ====================

    private Map<String, Object> callMicroservice(String path, Map<String, Object> payload) {
        try {
            String url = errorAnalysisBaseUrl.replaceAll("/+$", "") + path;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = restTemplate.postForEntity(url, entity, Map.class).getBody();
            return responseBody;
        } catch (Exception e) {
            logger.error("Microservice call failed: path={}, error={}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 检查 learning payload 的 errorHistory 是否包含有效错误记录（count >= 1）
     */
    private boolean hasErrors(Map<String, Object> learningPayload) {
        if (learningPayload == null) return false;
        Object history = learningPayload.get("errorHistory");
        if (!(history instanceof List)) return false;
        List<?> list = (List<?>) history;
        return !list.isEmpty();
    }

    private String safeString(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String s = value.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<?> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return null;
        }
    }
}
