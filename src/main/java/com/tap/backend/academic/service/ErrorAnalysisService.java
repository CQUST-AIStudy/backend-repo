package com.tap.backend.academic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.academic.dao.AiErrorAnalysisReportDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.AiErrorAnalysisReport;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.StudentSubmissionAttempt;
import com.tap.backend.email.EmailService;
import com.tap.backend.email.ExperimentWarningSummary;
import com.tap.backend.email.ProblemWarningInfo;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
    private static final String REDIS_SYNC_KEY_PREFIX = "ai:sync:";
    private static final long REDIS_TTL_HOURS = 24;
    private static final long REDIS_SYNC_TTL_HOURS = 1;
    private static final SimpleDateFormat ISO_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private TeacherExperimentQueryDao teacherExperimentQueryDao;

    @Autowired
    private AiErrorAnalysisReportDao reportDao;

    @Autowired
    private ExperimentService experimentService;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EmailService emailService;

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
                attempts = teacherExperimentQueryDao.findSubmissionAttemptsFromRaw(studentNo, experimentId);
            }

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
            if (totalErrors >= 1) {
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
     * 同步：从 DB 构建 payload → 调微服务 → 缓存 1h → 返回
     * @param forceRefresh 跳过缓存，强制重新分析
     */
    public Map<String, Object> analyzeErrorFromDb(String studentNo, String studentName,
                                                   int experimentId, boolean forceRefresh) {
        // ── 缓存命中 ──
        if (!forceRefresh) {
            Map<String, Object> cached = getSyncCache(studentNo, experimentId, "error");
            if (cached != null) {
                logger.info("analyzeErrorFromDb: cache hit for student={}, experiment={}", studentNo, experimentId);
                return cached;
            }
        }

        List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        // ── fallback: 主表无数据时走 pta_raw 三表桥接 ──
        if (attempts == null || attempts.isEmpty()) {
            attempts = teacherExperimentQueryDao.findSubmissionAttemptsFromRaw(studentNo, experimentId);
            logger.info("analyzeErrorFromDb: {} raw fallback rows for student={}, experiment={}",
                    attempts != null ? attempts.size() : 0, studentNo, experimentId);
        }
        // ── 通用优化：raw 数据中若代码含多题标记，拆分为每题独立提交 ──
        if (attempts != null && !attempts.isEmpty()) {
            String firstCode = attempts.get(0).getCode();
            if (firstCode != null && firstCode.contains("第") && firstCode.contains("题")) {
                String codeBlob = fetchCodeOnly(studentNo, experimentId);
                if (codeBlob != null && !codeBlob.isBlank()) {
                    List<String> chunks = splitCodePerProblem(codeBlob);
                    if (chunks.size() > 1) {
                        logger.info("analyzeErrorFromDb: split code blob into {} per-problem submissions for student={}, experiment={}",
                                chunks.size(), studentNo, experimentId);
                        attempts = buildAttemptsFromCode(codeBlob, experimentId);
                    }
                }
            }
        }
        if (attempts == null || attempts.isEmpty()) {
            // ── second fallback: student_code 表有代码但无判题记录 ──
            String codeOnly = fetchCodeOnly(studentNo, experimentId);
            if (codeOnly != null && !codeOnly.isBlank()) {
                logger.info("analyzeErrorFromDb: code-only fallback for student={}, experiment={}",
                        studentNo, experimentId);
                attempts = buildAttemptsFromCode(codeOnly, experimentId);
            }
        }
        if (attempts == null || attempts.isEmpty()) {
            logger.info("analyzeErrorFromDb: no submissions for student={}, experiment={}", studentNo, experimentId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("aiGenerated", false);
            result.put("overallAssessment", "暂无提交记录，完成PTA平台实验后可使用AI错误分析功能。");
            result.put("errorCategories", new ArrayList<>());
            result.put("learningSuggestions", new ArrayList<>());
            result.put("latestCode", null);
            result.put("latestJudgeStatus", null);
            return result;
        }
        Experiment experiment = experimentService.findExperimentById(experimentId);
        List<Map<String, Object>> submissions = buildSubmissionList(attempts);

        Map<String, Object> payload = buildErrorPayload(studentNo, studentName,
                experimentId, experiment != null ? experiment.getName() : ("实验" + experimentId), submissions, attempts);
        // ── debug: 打印发给 AI 的 submissions 概况 ──
        logger.info("analyzeError payload: {} submissions, problemCount={}",
                submissions.size(), payload.getOrDefault("problemCount", "N/A"));
        if (!submissions.isEmpty()) {
            Map<String, Object> first = submissions.get(0);
            String firstCode = (String) first.getOrDefault("code", "");
            logger.info("analyzeError — submission[0]: problemTitle={}, codeLen={}, judgeStatus={}",
                    first.get("problemTitle"), firstCode.length(), first.get("judgeStatus"));
        }
        Map<String, Object> result = callMicroservice("/analyze/error", payload);

        // ── 缓存 1h ──
        if (result != null) {
            cleanOldReports(studentNo, experimentId, "ERROR");
            putSyncCache(studentNo, experimentId, "error", result);
            saveReport(studentNo, experimentId,
                    experiment != null ? experiment.getName() : ("实验" + experimentId),
                    "ERROR", result);
        }
        return result;
    }

    public Map<String, Object> learningSuggestFromDb(String studentNo, String studentName, int experimentId) {
        List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        if (attempts == null || attempts.isEmpty()) {
            attempts = teacherExperimentQueryDao.findSubmissionAttemptsFromRaw(studentNo, experimentId);
        }
        Map<String, Object> payload = buildLearningPayload(studentNo, studentName, attempts);
        if (!hasErrors(payload)) {
            logger.info("Learning suggest skipped: no errors for student={}, experiment={}", studentNo, experimentId);
            return null;
        }
        return callMicroservice("/analyze/learning", payload);
    }

    public Map<String, Object> warningAnalyzeFromDb(String studentNo, String studentName,
                                                     int experimentId, boolean forceRefresh) {
        // ── 缓存命中 ──
        if (!forceRefresh) {
            Map<String, Object> cached = getSyncCache(studentNo, experimentId, "warning");
            if (cached != null) {
                logger.info("warningAnalyzeFromDb: cache hit for student={}, experiment={}", studentNo, experimentId);
                return cached;
            }
        }

        List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        if (attempts == null || attempts.isEmpty()) {
            attempts = teacherExperimentQueryDao.findSubmissionAttemptsFromRaw(studentNo, experimentId);
        }
        String experimentName = resolveExperimentName(experimentId);
        Map<String, Object> result = new LinkedHashMap<>();

        if (attempts == null || attempts.isEmpty()) {
            result.put("triggered", false);
            result.put("totalErrors", 0);
            result.put("message", "暂无提交记录");
            return result;
        }

        List<ProblemWarningInfo> triggeredProblems = analyzeExperimentProblems(
                studentNo, studentName, experimentId, experimentName, attempts);

        // 筛选真正触发了 AI 预警的题目（suggestedActions != null 表示 ≥3 错误且调了 AI）
        List<ProblemWarningInfo> aiTriggered = new ArrayList<>();
        for (ProblemWarningInfo p : triggeredProblems) {
            if (p.getSuggestedActions() != null && !p.getSuggestedActions().isEmpty()) {
                aiTriggered.add(p);
            }
        }

        if (aiTriggered.isEmpty()) {
            result.put("triggered", false);
            int totalErrors = countErrors(attempts);
            result.put("totalErrors", totalErrors);
            result.put("problemCount", triggeredProblems.size());
            if (totalErrors > 0) {
                result.put("message", "错误次数较少（< 3次/题），暂未触发预警，继续加油！");
            } else {
                result.put("message", "未检测到错误，表现优秀！");
            }
            return result;
        }

        // 聚合：取最高等级
        String highestLevel = "LOW";
        for (ProblemWarningInfo p : aiTriggered) {
            String lv = p.getLevel();
            if ("HIGH".equals(lv)) {
                highestLevel = "HIGH";
                break;
            } else if ("MEDIUM".equals(lv)) {
                highestLevel = "MEDIUM";
            }
        }

        // 合并 warning message
        StringBuilder msgBuilder = new StringBuilder();
        StringBuilder noteBuilder = new StringBuilder();
        java.util.Set<String> actionSet = new java.util.LinkedHashSet<>();

        for (int i = 0; i < aiTriggered.size(); i++) {
            ProblemWarningInfo p = aiTriggered.get(i);
            if (i > 0) {
                msgBuilder.append("；");
                noteBuilder.append("；");
            }
            msgBuilder.append("「").append(p.getProblemTitle()).append("」")
                    .append("共提交").append(p.getTotalSubmissions()).append("次");
            noteBuilder.append("「").append(p.getProblemTitle()).append("」")
                    .append(": ").append(p.getWarningType())
                    .append(" AC:").append(p.getAcceptedCount())
                    .append("/").append(p.getTotalSubmissions());
            if (p.getSuggestedActions() != null) {
                actionSet.addAll(p.getSuggestedActions());
            }
        }
        msgBuilder.append("，建议查看详细分析。");

        result.put("triggered", true);
        result.put("level", highestLevel);
        result.put("warningMessage", msgBuilder.toString());
        result.put("teacherNote", noteBuilder.toString());
        result.put("suggestedActions", new ArrayList<>(actionSet));
        result.put("problemCount", aiTriggered.size());

        // ── 缓存 1h ──
        cleanOldReports(studentNo, experimentId, "WARNING");
        putSyncCache(studentNo, experimentId, "warning", result);
        saveReport(studentNo, experimentId, experimentName, "WARNING", result);
        return result;
    }

    /**
     * 登录后自动扫描所有"进行中"的实验，对 ≥3 次错误的实验做预警分析，
     * 将多个实验的预警结果打包成一封邮件发送。
     */
    @Async("aiExecutor")
    public void scanActiveExperimentsAndWarn(String studentNo, String studentName) {
        logger.info("[预警扫描] 开始全盘预警分析 student={}", studentNo);

        // 限频：同一学生 2h 内不重复扫描（Redis key 与 EmailService 共用）
        if (redisTemplate != null) {
            try {
                String key = "tap:login_warning_scan:" + studentNo;
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(key, "1", 2, TimeUnit.HOURS);
                if (!Boolean.TRUE.equals(acquired)) {
                    logger.info("[预警扫描] ⏱ 2h内已扫描过 student={}，跳过", studentNo);
                    return;
                }
            } catch (Exception e) {
                logger.warn("[预警扫描] 限频检查异常，继续执行: {}", e.getMessage());
            }
        }

        try {
            List<Map<String, Object>> activeExperiments =
                    teacherExperimentQueryDao.findActiveExperimentsForStudent(studentNo);
            if (activeExperiments == null || activeExperiments.isEmpty()) {
                logger.info("[预警扫描] 无进行中实验 student={}", studentNo);
                return;
            }

            logger.info("[预警扫描] 找到 {} 个进行中实验 student={}", activeExperiments.size(), studentNo);

            List<ExperimentWarningSummary> triggered = new ArrayList<>();
            for (Map<String, Object> exp : activeExperiments) {
                Object idObj = exp.get("experimentId");
                int expId = idObj instanceof Number ? ((Number) idObj).intValue() : Integer.parseInt(String.valueOf(idObj));
                String expName = exp.get("experimentName") instanceof String
                        ? (String) exp.get("experimentName") : ("实验" + expId);

                List<StudentSubmissionAttempt> attempts =
                        teacherExperimentQueryDao.findSubmissionAttemptsForErrorAnalysis(studentNo, expId);
                if (attempts == null || attempts.isEmpty()) {
                    attempts = teacherExperimentQueryDao.findSubmissionAttemptsFromRaw(studentNo, expId);
                }
                if (attempts == null || attempts.isEmpty()) continue;

                List<ProblemWarningInfo> problems = analyzeExperimentProblems(
                        studentNo, studentName, expId, expName, attempts);
                if (!problems.isEmpty()) {
                    triggered.add(new ExperimentWarningSummary(expId, expName, problems));
                }
            }

            if (!triggered.isEmpty()) {
                logger.info("[预警扫描] {} 个实验触发预警，发送邮件...", triggered.size());
                emailService.sendMultiExperimentWarningEmail(studentNo, studentName, triggered);
            } else {
                logger.info("[预警扫描] 无实验触发预警 student={}", studentNo);
            }
        } catch (Exception e) {
            logger.error("[预警扫描] 失败 student={}: {}", studentNo, e.getMessage(), e);
        }
    }

    /**
     * 分析单个实验的所有提交，按题目分组，对 ≥3 次错误的题目调 AI 微服务获取建议。
     * @return 触发预警的题目列表（仅 errorCount ≥ 1 的题目，空列表表示无预警）
     */
    private List<ProblemWarningInfo> analyzeExperimentProblems(
            String studentNo, String studentName, int experimentId, String experimentName,
            List<StudentSubmissionAttempt> attempts) {
        List<ProblemWarningInfo> triggeredProblems = new ArrayList<>();

        // 按题目分组
        Map<Long, List<StudentSubmissionAttempt>> byProblem = attempts.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getProblemId() != null ? a.getProblemId() : 0L));

        for (Map.Entry<Long, List<StudentSubmissionAttempt>> entry : byProblem.entrySet()) {
            List<StudentSubmissionAttempt> problemAttempts = entry.getValue();
            int errorCount = countErrors(problemAttempts);
            if (errorCount < 1) continue;

            String problemTitle = resolveProblemTitle(problemAttempts);
            int accepted = (int) problemAttempts.stream()
                    .filter(a -> "ACCEPTED".equalsIgnoreCase(a.getJudgeStatus())).count();
            Map<String, Integer> statusCounts = buildStatusCounts(problemAttempts);
            List<String> suggestedActions = null;
            String level = "LOW";
            String warningType = "OK";

            // 仅 >=3 错误时调微服务获取 AI 建议
            if (errorCount >= 1) {
                Map<String, Object> payload = buildWarningPayload(studentNo, studentName,
                        experimentId, experimentName, problemAttempts);
                payload.put("problemTitle", problemTitle);

                Map<String, Object> responseBody = callMicroservice("/analyze/warning", payload);

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) responseBody.getOrDefault("data", responseBody);
                @SuppressWarnings("unchecked")
                Map<String, Object> warning = (Map<String, Object>) data.get("warning");
                if (warning == null) warning = data;

                if (Boolean.TRUE.equals(warning.get("autoNotify"))) {
                    @SuppressWarnings("unchecked")
                    List<String> actions = (List<String>) warning.get("suggestedActions");
                    suggestedActions = actions;
                    level = safeString(warning.get("level"), "MEDIUM");
                    warningType = safeString(warning.get("warningType"), "FREQUENT_FAILURE");
                }
            }

            triggeredProblems.add(new ProblemWarningInfo(
                    entry.getKey(), problemTitle,
                    problemAttempts.size(), accepted,
                    statusCounts, level, warningType, suggestedActions));
        }

        return triggeredProblems;
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
            String code = cleanCode(a.getCode());
            if (code.isEmpty()) {
                code = "// 代码未保存，仅保留PTA判题记录，请根据判题结果分析";
            }
            sub.put("errorMessage", buildErrorMessage(a));
            sub.put("code", code);
            sub.put("problemTitle", a.getProblemTitle() != null ? a.getProblemTitle() : "");
            if (a.getProblemId() != null) {
                sub.put("problemId", a.getProblemId());
            }
            if (a.getSubmittedAt() != null) {
                sub.put("submittedAt", ISO_FORMAT.format(a.getSubmittedAt()));
            }
            submissions.add(sub);
        }
        return submissions;
    }

    /**
     * 从 rawJson + judgeStatus + score 拼出 AI 可用的报错上下文。
     */
    private String buildErrorMessage(StudentSubmissionAttempt a) {
        StringBuilder sb = new StringBuilder();
        String status = a.getJudgeStatus() != null ? a.getJudgeStatus() : "UNKNOWN";
        sb.append("判题: ").append(status);
        if (a.getScore() != null) sb.append(", 得分: ").append(a.getScore());
        if (a.getRuntimeMs() != null) sb.append(", 运行时间: ").append(a.getRuntimeMs()).append("ms");
        if (a.getMemoryKb() != null) sb.append(", 内存: ").append(a.getMemoryKb()).append("KB");
        String compiler = a.getCompiler();
        if (compiler != null && !compiler.isEmpty()) sb.append(", 编译器: ").append(compiler);
        // 题目描述（来自 pta_problem_detail）
        if (a.getRawJson() != null && a.getRawJson().contains("problemDesc")) {
            try {
                Map<String, Object> raw = objectMapper.readValue(a.getRawJson(), Map.class);
                Object desc = raw.get("problemDesc");
                if (desc instanceof String s && !s.isBlank()) {
                    sb.append("\n题目描述: ").append(s);
                }
            } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    private int countErrors(List<StudentSubmissionAttempt> attempts) {
        if (attempts == null) return 0;
        return (int) attempts.stream()
                .filter(a -> a.getJudgeStatus() != null && !"ACCEPTED".equalsIgnoreCase(a.getJudgeStatus()))
                .count();
    }

    /**
     * 清洗单题代码：去掉PTA导出的测试表格。
     */
    private String cleanCode(String code) {
        if (code == null || code.isEmpty()) return "";
        return code
                .replace("\r\n", "\n")
                .lines()
                .filter(line -> !line.matches("^\\s*\\|\\s*---"))       // 表头分隔线
                .filter(line -> !line.matches("^\\s*\\|.*\\|\\s*$"))    // 测试点表格行
                .map(String::stripTrailing)
                .collect(java.util.stream.Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 按"第N题如下:"分隔符拆分代码 blob，每题清洗后返回。
     */
    private List<String> splitCodePerProblem(String rawCode) {
        List<String> chunks = new ArrayList<>();
        if (rawCode == null || rawCode.isEmpty()) return chunks;

        // 按 "第N题" 分隔
        String[] parts = rawCode.split("(?=第\\d+题)");
        for (String part : parts) {
            String cleaned = cleanCode(part.replaceFirst("^第\\d+题.*?[\r\n]+", ""));
            if (!cleaned.isBlank()) {
                chunks.add(cleaned);
            }
        }
        return chunks;
    }

    // ── 最后兜底：student_code 有代码但无判题记录 ──

    /**
     * 从 student_code 表取代码（学号+实验ID）。
     */
    private String fetchCodeOnly(String studentNo, int experimentId) {
        try {
            String code = teacherExperimentQueryDao.findCodeOnly(studentNo, experimentId);
            return (code != null && !code.isBlank()) ? code : null;
        } catch (Exception e) {
            logger.debug("Code-only fallback query failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 拆分代码 blob 为每题一条虚拟提交，关联题目标题和题目描述。
     */
    private List<StudentSubmissionAttempt> buildAttemptsFromCode(String codeBlob, int experimentId) {
        List<String> chunks = splitCodePerProblem(codeBlob);
        List<Map<String, Object>> problems = teacherExperimentQueryDao.findProblemInfoForExperiment(experimentId);
        List<StudentSubmissionAttempt> list = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            StudentSubmissionAttempt a = new StudentSubmissionAttempt();
            a.setCode(chunks.get(i));
            a.setJudgeStatus("UNKNOWN");
            a.setCompiler("");
            a.setSubmittedAt(new Date());

            if (i < problems.size()) {
                Map<String, Object> p = problems.get(i);
                Object pid = p.get("id");
                if (pid instanceof Number) a.setProblemId(((Number) pid).longValue());
                a.setProblemTitle((String) p.getOrDefault("title", ""));
                // 题目描述拼入 errorMessage
                String desc = (String) p.getOrDefault("description", "");
                if (!desc.isBlank() && desc.length() < 2000) {
                    a.setRawJson("{\"problemDesc\":\"" + desc.replace("\"", "\\\"") + "\"}");
                }
            }
            list.add(a);
        }
        return list;
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
        // 提取去重的题目标题列表
        LinkedHashSet<String> problemTitles = new LinkedHashSet<>();
        if (attempts != null) {
            for (StudentSubmissionAttempt a : attempts) {
                String t = a.getProblemTitle();
                if (t != null && !t.isBlank()) problemTitles.add(t);
            }
        }
        String problemTitle = problemTitles.isEmpty() ? "编程练习"
                : String.join("；", problemTitles);
        payload.put("problemTitle", problemTitle);
        payload.put("problemCount", problemTitles.size());
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

    // ==================== 预警邮件 ====================

    private String resolveExperimentName(int experimentId) {
        try {
            String name = teacherExperimentQueryDao.findOfferingName(experimentId);
            if (name != null && !name.isBlank()) return name.trim();
        } catch (Exception e) {
            logger.warn("Failed to resolve experiment name for id={}: {}", experimentId, e.getMessage());
        }
        return "实验" + experimentId;
    }

    private Map<String, Integer> buildStatusCounts(List<StudentSubmissionAttempt> attempts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StudentSubmissionAttempt a : attempts) {
            String status = a.getJudgeStatus();
            if (status == null || "ACCEPTED".equalsIgnoreCase(status)) continue;
            counts.merge(status.toUpperCase(), 1, Integer::sum);
        }
        return counts;
    }

    private String resolveProblemTitle(List<StudentSubmissionAttempt> problemAttempts) {
        for (StudentSubmissionAttempt a : problemAttempts) {
            if (a.getProblemTitle() != null && !a.getProblemTitle().isBlank()) {
                return a.getProblemTitle().trim();
            }
        }
        return "未知题目";
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

    /** 删掉同学生+实验+类型的旧报告，避免重复堆积 */
    private void cleanOldReports(String studentNo, int experimentId, String reportType) {
        try {
            reportDao.deleteByStudentExperimentAndType(studentNo, experimentId, reportType);
        } catch (Exception e) {
            logger.warn("Failed to clean old {} reports: {}", reportType, e.getMessage());
        }
    }

    // ==================== 同步端点缓存（1h TTL） ====================

    private String syncCacheKey(String studentNo, int experimentId, String type) {
        return REDIS_SYNC_KEY_PREFIX + studentNo + ":" + experimentId + ":" + type;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSyncCache(String studentNo, int experimentId, String type) {
        if (redisTemplate == null) return null;
        try {
            String json = redisTemplate.opsForValue().get(syncCacheKey(studentNo, experimentId, type));
            if (json == null || json.isEmpty()) return null;
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.debug("Sync cache miss: {}/{}/{}", studentNo, experimentId, type);
            return null;
        }
    }

    private void putSyncCache(String studentNo, int experimentId, String type, Map<String, Object> data) {
        if (redisTemplate == null || data == null || data.isEmpty()) return;
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(syncCacheKey(studentNo, experimentId, type), json,
                    REDIS_SYNC_TTL_HOURS, TimeUnit.HOURS);
            logger.debug("Sync cache set: {}/{}/{}", studentNo, experimentId, type);
        } catch (Exception e) {
            logger.warn("Sync cache write failed: {}", e.getMessage());
        }
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
            if (responseBody == null) {
                throw new IllegalStateException(
                        "Error analysis microservice returned an empty response: " + path);
            }
            return responseBody;
        } catch (Exception e) {
            logger.error("Microservice call failed: path={}, error={}", path, e.getMessage());
            throw new IllegalStateException(
                    "Error analysis microservice call failed: " + path,
                    e);
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
