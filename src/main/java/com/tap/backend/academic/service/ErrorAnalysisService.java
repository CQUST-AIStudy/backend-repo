package com.tap.backend.academic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.academic.dao.AiErrorAnalysisReportDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.AiErrorAnalysisReport;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.StudentSubmissionAttempt;
import com.tap.backend.ai.AiProvider;
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
    private static final int AI_ERROR_MAX_ATTEMPTS = 8;
    private static final int AI_ERROR_MAX_CODE_CHARS = 1200;
    private static final int AI_ERROR_MAX_ERROR_MESSAGE_CHARS = 500;

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

    @Autowired(required = false)
    private AiProvider aiProvider;

    @Value("${tap.error-analysis.base-url:http://127.0.0.1:8002}")
    private String errorAnalysisBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 学生级学习建议生成中防重集合：同一学号同时只允许一个生成任务，避免并发重复调微服务与重复落库 */
    private final java.util.Set<String> pendingLearningProfile = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
            Map<String, Object> errorResult = callMicroserviceOrNull("/analyze/error", errorPayload);
            if (isEmptyAnalysisResponse(errorResult)) {
                errorResult = buildAiModelErrorAnalysisResult(studentNo, studentName,
                        experimentId, experimentName, attempts, submissions);
            }
            if (isEmptyAnalysisResponse(errorResult)) {
                errorResult = buildRuleFallbackErrorAnalysisResult(studentNo, studentName,
                        experimentId, experimentName, attempts, submissions);
            }
            errorResult = normalizeErrorAnalysisResult(errorResult, attempts);
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
            if (cached != null && hasProblemAnalyses(cached)) {
                logger.info("analyzeErrorFromDb: cache hit for student={}, experiment={}", studentNo, experimentId);
                return normalizeErrorAnalysisResult(cached, null);
            } else if (cached != null) {
                logger.info("analyzeErrorFromDb: cached result has no per-problem analysis; rebuilding for student={}, experiment={}",
                        studentNo, experimentId);
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
            result.put("generationMode", "JUDGE_RESULT");
            result.put("provider", "system");
            result.put("fallbackReason", "NO_SUBMISSION");
            result.put("overallAssessment", "暂无提交记录，完成PTA平台实验后可使用AI错误分析功能。");
            result.put("errorCategories", new ArrayList<>());
            result.put("learningSuggestions", new ArrayList<>());
            result.put("problemAnalyses", new ArrayList<>());
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
        Map<String, Object> result = callMicroserviceOrNull("/analyze/error", payload);
        if (isEmptyAnalysisResponse(result)) {
            logger.warn("analyzeErrorFromDb: microservice unavailable, using backend AI provider for student={}, experiment={}",
                    studentNo, experimentId);
            result = buildAiModelErrorAnalysisResult(studentNo, studentName, experimentId,
                    experiment != null ? experiment.getName() : ("实验" + experimentId), attempts, submissions);
        }
        if (isEmptyAnalysisResponse(result)) {
            logger.warn("analyzeErrorFromDb: AI provider unavailable, using rule fallback for student={}, experiment={}",
                    studentNo, experimentId);
            result = buildRuleFallbackErrorAnalysisResult(studentNo, studentName, experimentId,
                    experiment != null ? experiment.getName() : ("实验" + experimentId), attempts, submissions);
        }
        result = normalizeErrorAnalysisResult(result, attempts);

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

    /**
     * 学生级学习建议（跨实验汇总）：数据库优先 + 落库，避免每次打开页面都实时调微服务。
     * <p>
     * 复用 AiErrorAnalysisReport 表，以 experimentId=0 表示"学生级"，reportType=LEARNING 区分。
     * <ul>
     *   <li>forceRefresh=false（页面默认打开）：先查库，命中即返回（毫秒级）；未命中才用 payload 调微服务生成并落库。</li>
     *   <li>forceRefresh=true（"重新生成"按钮）：跳过缓存，强制重新生成并覆盖落库。</li>
     *   <li>single-flight：同一学生若已有生成在进行中，后到的请求直接返回库中旧结果（若有），避免并发重复调微服务与重复落库。</li>
     * </ul>
     */
    public Map<String, Object> learningSuggestCached(String studentNo, Map<String, Object> payload, boolean forceRefresh) {
        // 1. 非强制：先查库，命中即返回（不依赖 payload，解决"画像无弱点但库里有旧建议"读不到的问题）
        if (!forceRefresh && studentNo != null) {
            Map<String, Object> cached = findStoredLearning(studentNo);
            if (cached != null) {
                logger.info("learningSuggestCached: DB cache hit for student={}", studentNo);
                return cached;
            }
        }
        // 2. 需要生成：校验 payload 含有效 errorHistory，否则不徒劳调微服务
        if (!hasValidLearningPayload(payload)) {
            logger.info("learningSuggestCached: no valid errorHistory, skip generation for student={}", studentNo);
            return null;
        }
        // 3. single-flight：同一学生正在生成中，后到请求返回库中旧结果（可能为 null）
        if (studentNo != null && !pendingLearningProfile.add(studentNo)) {
            logger.info("learningSuggestCached: in-flight, return stale for student={}", studentNo);
            return findStoredLearning(studentNo);
        }
        try {
            Map<String, Object> result = callMicroservice("/analyze/learning", payload);
            if (result != null && studentNo != null) {
                cleanOldReports(studentNo, 0, "LEARNING");
                saveReport(studentNo, 0, "学生学习画像建议", "LEARNING", result);
            }
            return result;
        } finally {
            if (studentNo != null) pendingLearningProfile.remove(studentNo);
        }
    }

    /** 查询学生级学习建议缓存，命中返回 {data: {...}}，未命中返回 null。 */
    private Map<String, Object> findStoredLearning(String studentNo) {
        if (studentNo == null) return null;
        try {
            List<AiErrorAnalysisReport> reports = reportDao.findByStudentAndExperiment(studentNo, 0);
            if (reports != null) {
                for (AiErrorAnalysisReport r : reports) {
                    if ("LEARNING".equals(r.getReportType())) {
                        Map<String, Object> wrapped = new LinkedHashMap<>();
                        wrapped.put("data", storedLearningReportToMap(r));
                        return wrapped;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("findStoredLearning failed: {}", e.getMessage());
        }
        return null;
    }

    /** 校验 payload 是否含可用于生成的 errorHistory（非空 List）。 */
    private boolean hasValidLearningPayload(Map<String, Object> payload) {
        if (payload == null) return false;
        Object eh = payload.get("errorHistory");
        return eh instanceof List && !((List<?>) eh).isEmpty();
    }

    /** 将已存储的学生级学习建议报告转为前端期望的结构（与微服务 /analyze/learning 的 data 字段对齐）。 */
    private Map<String, Object> storedLearningReportToMap(AiErrorAnalysisReport r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aiGenerated", Boolean.TRUE.equals(r.getAiGenerated()));
        if (r.getSummaryMessage() != null) data.put("summaryMessage", r.getSummaryMessage());
        data.put("weakPoints", parseJsonArray(r.getWeakPointsJson()));
        data.put("studyPlan", parseJsonArray(r.getStudyPlanJson()));
        data.put("recommendedProblems", parseJsonArray(r.getRecommendedProblemsJson()));
        data.put("learningSuggestions", parseJsonArray(r.getLearningSuggestionsJson()));
        if (r.getCreatedAt() != null) data.put("generatedAt", ISO_FORMAT.format(r.getCreatedAt()));
        return data;
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

                Map<String, Object> responseBody = callMicroserviceOrNull("/analyze/warning", payload);
                Map<String, Object> data = extractDataMap(responseBody);
                if (data == null || data.isEmpty()) {
                    logger.warn("Warning analysis unavailable; skipping notification for student={}, experiment={}, problem={}",
                            studentNo, experimentId, problemTitle);
                } else {
                    Object rawWarning = data.get("warning");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> warning = rawWarning instanceof Map<?, ?>
                            ? (Map<String, Object>) rawWarning : data;

                    if (Boolean.TRUE.equals(warning.get("autoNotify"))) {
                        Object rawActions = warning.get("suggestedActions");
                        if (rawActions instanceof List<?> actions) {
                            suggestedActions = actions.stream()
                                    .filter(String.class::isInstance)
                                    .map(String.class::cast)
                                    .toList();
                        }
                        level = safeString(warning.get("level"), "MEDIUM");
                        warningType = safeString(warning.get("warningType"), "FREQUENT_FAILURE");
                    }
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
            data.put("createdAt", r.getCreatedAt() != null ? ISO_FORMAT.format(r.getCreatedAt()) : null);
            Map<String, Object> rawData = extractDataMap(parseJsonObject(r.getRawResponseJson()));
            applyStoredGenerationMetadata(data, rawData, r);

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
            if (rawData != null && rawData.get("problemAnalyses") instanceof List<?>) {
                data.put("problemAnalyses", rawData.get("problemAnalyses"));
            }

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
                .filter(a -> a.getJudgeStatus() != null && !isAcceptedStatus(a.getJudgeStatus()))
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
        String generationMode = normalizeGenerationMode(data.get("generationMode"));
        report.setAiGenerated("AI_MODEL".equals(generationMode) || Boolean.TRUE.equals(data.get("aiGenerated")));
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
            String status = normalizeJudgeStatus(a.getJudgeStatus());
            if (isAcceptedStatus(status)) continue;
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

    private Map<String, Object> callMicroserviceOrNull(String path, Map<String, Object> payload) {
        try {
            return callMicroservice(path, payload);
        } catch (Exception e) {
            logger.warn("Optional error-analysis microservice unavailable: path={}, error={}",
                    path, e.getMessage());
            return null;
        }
    }

    private boolean isEmptyAnalysisResponse(Map<String, Object> responseBody) {
        Map<String, Object> data = extractDataMap(responseBody);
        return data == null || data.isEmpty();
    }

    private Map<String, Object> buildAiModelErrorAnalysisResult(
            String studentNo,
            String studentName,
            int experimentId,
            String experimentName,
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> submissions) {
        if (!isRealAiProviderAvailable() || !hasNonAcceptedAttempt(attempts)) {
            return null;
        }

        try {
            String prompt = buildAiErrorAnalysisPrompt(studentNo, studentName,
                    experimentId, experimentName, attempts, submissions);
            String raw = aiProvider.chat(prompt, null);
            Map<String, Object> data = parseModelJsonObject(raw);
            data = extractDataMap(data);
            if (data == null || data.isEmpty()) return null;

            normalizeAiErrorAnalysisData(data, studentNo, studentName,
                    experimentId, experimentName, attempts, submissions);
            if (!hasUsableAiErrorAnalysis(data)) {
                logger.warn("AI provider returned unusable error analysis for student={}, experiment={}",
                        studentNo, experimentId);
                return null;
            }

            logger.info("AI provider generated error analysis: provider={}, model={}, student={}, experiment={}",
                    aiProvider.name(), aiProvider.model(), studentNo, experimentId);
            return data;
        } catch (Exception e) {
            logger.warn("AI provider error analysis failed: provider={}, student={}, experiment={}, error={}",
                    aiProvider != null ? aiProvider.name() : "none", studentNo, experimentId, e.getMessage());
            return null;
        }
    }

    private boolean isRealAiProviderAvailable() {
        if (aiProvider == null) return false;
        String name = aiProvider.name();
        return name != null && !name.isBlank() && !"mock".equalsIgnoreCase(name);
    }

    private boolean hasNonAcceptedAttempt(List<StudentSubmissionAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) return false;
        for (StudentSubmissionAttempt attempt : attempts) {
            if (attempt != null && !isAcceptedStatus(attempt.getJudgeStatus())) {
                return true;
            }
        }
        return false;
    }

    private String buildAiErrorAnalysisPrompt(
            String studentNo,
            String studentName,
            int experimentId,
            String experimentName,
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> submissions) {
        return """
                你是高校程序设计课程的 AI 错误分析助手。请只基于下面给出的真实提交记录、判题状态、题目标题和代码进行分析，不要编造知识点、分数或未提供的测试点。

                输出必须是严格 JSON，不要 Markdown 代码块，不要解释。JSON schema：
                {
                  "severity": "HIGH|MEDIUM|LOW",
                  "overallAssessment": "用中文说明整个实验的主要错误表现和下一步修复重点",
                  "errorCategories": [
                    {
                      "type": "COMPILE_ERROR|RUNTIME_ERROR|WRONG_ANSWER|TIME_LIMIT_EXCEEDED|MEMORY_LIMIT_EXCEEDED|SEGMENTATION_FAULT|UNKNOWN",
                      "count": 1,
                      "isSystemic": false,
                      "rootCause": "只能写提交证据能支持的原因，证据不足要说明需结合最小样例核验",
                      "specificIssues": ["受影响题目或可从代码/判题状态直接看出的具体问题"],
                      "suggestions": ["可执行的修改步骤"]
                    }
                  ],
                  "learningSuggestions": [
                    {
                      "priority": "HIGH|MEDIUM|LOW",
                      "topic": "需要补的能力点",
                      "reason": "对应真实判题证据",
                      "suggestedResources": "具体练习或复查动作"
                    }
                  ],
                  "problemAnalyses": [
                    {
                      "problemId": 123,
                      "problemTitle": "必须与证据中的题目对应",
                      "severity": "HIGH|MEDIUM|LOW",
                      "overallAssessment": "只分析该 problemId 的代码和判题记录",
                      "errorCategories": [],
                      "learningSuggestions": []
                    }
                  ]
                }

                problemAnalyses 必须按 problemId 分题输出。只能使用 selectedErrorAttempts 中真实存在的 problemId，
                每个有错误证据的 problemId 最多输出一项，严禁把其他题目的错误、代码或建议混入当前题。

                学生：%s（%s）
                实验：%s（%d）
                真实提交证据：
                %s
                """.formatted(
                safeString(studentName, studentNo),
                safeString(studentNo, ""),
                safeString(experimentName, "实验" + experimentId),
                experimentId,
                compactErrorAnalysisEvidence(attempts, submissions));
    }

    private String compactErrorAnalysisEvidence(
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> submissions) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> affectedProblems = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<StudentSubmissionAttempt> safeAttempts = attempts != null ? attempts : new ArrayList<>();
        evidence.put("totalAttemptCount", safeAttempts.size());
        evidence.put("submittedPayloadCount", submissions != null ? submissions.size() : 0);
        for (int i = 0; i < safeAttempts.size(); i++) {
            StudentSubmissionAttempt attempt = safeAttempts.get(i);
            if (attempt == null) continue;
            String status = normalizeJudgeStatus(attempt.getJudgeStatus());
            statusCounts.merge(status, 1, Integer::sum);
            String problemTitle = safeString(attempt.getProblemTitle(), "");
            if (!isAcceptedStatus(status) && !problemTitle.isBlank()) {
                affectedProblems
                        .computeIfAbsent(status, ignored -> new LinkedHashSet<>())
                        .add(problemTitle);
            }
            if (isAcceptedStatus(status) || rows.size() >= AI_ERROR_MAX_ATTEMPTS) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attemptNo", i + 1);
            row.put("problemId", attempt.getProblemId());
            row.put("judgeStatus", status);
            row.put("problemTitle", problemTitle);
            row.put("score", attempt.getScore());
            row.put("runtimeMs", attempt.getRuntimeMs());
            row.put("memoryKb", attempt.getMemoryKb());
            row.put("errorMessage", limitText(buildErrorMessage(attempt), AI_ERROR_MAX_ERROR_MESSAGE_CHARS));
            row.put("codeSnippet", limitText(cleanCode(attempt.getCode()), AI_ERROR_MAX_CODE_CHARS));
            rows.add(row);
        }
        evidence.put("statusCounts", statusCounts);
        Map<String, Object> problemSummary = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : affectedProblems.entrySet()) {
            problemSummary.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        evidence.put("affectedProblems", problemSummary);
        evidence.put("selectedErrorAttempts", rows);
        evidence.put("selectionNote", "Only non-accepted representative attempts are sent to the model. Code is truncated to control tokens.");
        return toJson(evidence);
    }

    private String limitText(String value, int maxChars) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n// ... truncated for AI prompt";
    }

    private void normalizeAiErrorAnalysisData(
            Map<String, Object> data,
            String studentNo,
            String studentName,
            int experimentId,
            String experimentName,
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> submissions) {
        StudentSubmissionAttempt latest = latestAttempt(attempts);
        data.putIfAbsent("analysisId", "ai_error_" + UUID.randomUUID().toString().substring(0, 8));
        data.put("studentNo", studentNo);
        data.put("studentName", studentName != null && !studentName.isBlank() ? studentName : studentNo);
        data.put("experimentId", experimentId);
        data.put("experimentName", experimentName);
        data.putIfAbsent("severity", "MEDIUM");
        data.putIfAbsent("overallAssessment", "AI 已根据真实提交记录生成错误分析。");
        data.putIfAbsent("errorCategories", new ArrayList<>());
        data.putIfAbsent("learningSuggestions", new ArrayList<>());
        normalizeProblemAnalyses(data, attempts, "AI_MODEL");
        data.put("latestCode", latest != null ? cleanCode(latest.getCode()) : null);
        data.put("latestJudgeStatus", latest != null ? normalizeJudgeStatus(latest.getJudgeStatus()) : null);
        data.put("submissions", submissions != null ? submissions : new ArrayList<>());
        data.put("generationMode", "AI_MODEL");
        data.put("aiGenerated", true);
        data.put("provider", aiProvider.name());
        data.put("model", aiProvider.model());
        data.put("source", "ai_provider");
        normalizeErrorCategories(data);
    }

    private boolean hasUsableAiErrorAnalysis(Map<String, Object> data) {
        Object analyses = data.get("problemAnalyses");
        if (data.get("overallAssessment") == null || !(analyses instanceof List<?> list)) return false;
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(item -> "AI_MODEL".equals(item.get("generationMode"))
                        && item.get("errorCategories") instanceof List<?> categories
                        && !categories.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private void normalizeErrorCategories(Map<String, Object> data) {
        Object rawCategories = data.get("errorCategories");
        if (!(rawCategories instanceof List<?> categories)) return;
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : categories) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> category = new LinkedHashMap<>((Map<String, Object>) raw);
            category.put("type", normalizeJudgeStatus(safeString(category.get("type"), "UNKNOWN")));
            category.putIfAbsent("count", 1);
            category.putIfAbsent("isSystemic", false);
            category.putIfAbsent("specificIssues", new ArrayList<>());
            category.putIfAbsent("suggestions", new ArrayList<>());
            normalized.add(category);
        }
        data.put("errorCategories", normalized);
    }

    private StudentSubmissionAttempt latestAttempt(List<StudentSubmissionAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) return null;
        StudentSubmissionAttempt latest = null;
        for (StudentSubmissionAttempt attempt : attempts) {
            if (attempt == null) continue;
            if (latest == null
                    || (attempt.getSubmittedAt() == null && latest.getSubmittedAt() == null)
                    || (attempt.getSubmittedAt() != null
                    && (latest.getSubmittedAt() == null
                    || attempt.getSubmittedAt().after(latest.getSubmittedAt())))) {
                latest = attempt;
            }
        }
        return latest;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseModelJsonObject(String raw) throws JsonProcessingException {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first >= 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return objectMapper.readValue(text, Map.class);
    }

    private Map<String, Object> buildRuleFallbackErrorAnalysisResult(
            String studentNo,
            String studentName,
            int experimentId,
            String experimentName,
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> submissions) {
        List<StudentSubmissionAttempt> safeAttempts =
                attempts != null ? attempts : new ArrayList<>();
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> problemTitlesByType = new LinkedHashMap<>();
        StudentSubmissionAttempt latestAttempt = null;

        for (StudentSubmissionAttempt attempt : safeAttempts) {
            if (attempt == null) continue;
            if (latestAttempt == null
                    || (attempt.getSubmittedAt() == null && latestAttempt.getSubmittedAt() == null)
                    || (attempt.getSubmittedAt() != null
                    && (latestAttempt.getSubmittedAt() == null
                    || attempt.getSubmittedAt().after(latestAttempt.getSubmittedAt())))) {
                latestAttempt = attempt;
            }

            String status = normalizeJudgeStatus(attempt.getJudgeStatus());
            if (isAcceptedStatus(status)) continue;

            errorCounts.merge(status, 1, Integer::sum);
            String title = attempt.getProblemTitle();
            if (title != null && !title.isBlank()) {
                problemTitlesByType
                        .computeIfAbsent(status, ignored -> new LinkedHashSet<>())
                        .add(title.trim());
            }
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        List<Map<String, Object>> learningSuggestions = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue();
            List<String> affectedProblems = new ArrayList<>(
                    problemTitlesByType.getOrDefault(type, new LinkedHashSet<>()));

            Map<String, Object> category = new LinkedHashMap<>();
            category.put("type", type);
            category.put("count", count);
            category.put("isSystemic", count >= 2);
            category.put("rootCause", ruleRootCause(type));
            category.put("specificIssues", affectedProblems);
            category.put("suggestions", ruleSuggestions(type));
            categories.add(category);

            Map<String, Object> learningSuggestion = new LinkedHashMap<>();
            learningSuggestion.put("priority", count >= 2 ? "HIGH" : "MEDIUM");
            learningSuggestion.put("topic", ruleTopic(type));
            learningSuggestion.put("reason", "根据真实判题结果统计：" + count + " 次" + type);
            learningSuggestion.put("suggestedResources", ruleResource(type));
            learningSuggestions.add(learningSuggestion);
        }

        boolean allAccepted = !safeAttempts.isEmpty() && errorCounts.isEmpty()
                && safeAttempts.stream().allMatch(attempt ->
                attempt != null && isAcceptedStatus(attempt.getJudgeStatus()));
        String generationMode = allAccepted ? "JUDGE_RESULT" : "RULE_FALLBACK";
        String fallbackReason = allAccepted ? "JUDGE_RESULT_ONLY" : "AI_SERVICE_UNAVAILABLE";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysisId", "rule_fallback_" + UUID.randomUUID().toString().substring(0, 8));
        result.put("studentNo", studentNo);
        result.put("studentName", studentName != null && !studentName.isBlank() ? studentName : studentNo);
        result.put("experimentId", experimentId);
        result.put("experimentName", experimentName);
        result.put("severity", categories.isEmpty() ? "LOW"
                : categories.stream().anyMatch(category ->
                List.of("COMPILE_ERROR", "RUNTIME_ERROR", "SEGMENTATION_FAULT")
                        .contains(category.get("type")) || Boolean.TRUE.equals(category.get("isSystemic")))
                ? "HIGH" : "MEDIUM");
        result.put("overallAssessment", buildRuleOverallAssessment(allAccepted, safeAttempts, categories));
        result.put("errorCategories", categories);
        result.put("learningSuggestions", learningSuggestions);
        result.put("problemAnalyses", buildRuleProblemAnalyses(safeAttempts));
        result.put("latestCode", latestAttempt != null ? cleanCode(latestAttempt.getCode()) : null);
        result.put("latestJudgeStatus", latestAttempt != null
                ? normalizeJudgeStatus(latestAttempt.getJudgeStatus()) : null);
        result.put("submissions", submissions != null ? submissions : new ArrayList<>());
        result.put("generationMode", generationMode);
        result.put("aiGenerated", false);
        result.put("provider", "system");
        result.put("fallbackReason", fallbackReason);
        result.put("source", "rule_fallback");
        return result;
    }

    private String normalizeJudgeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) return "UNKNOWN";
        String status = rawStatus.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return switch (status) {
            case "AC", "ACCEPT", "ACCEPTED" -> "ACCEPTED";
            case "CE", "COMPILATION_ERROR", "COMPILE_ERROR" -> "COMPILE_ERROR";
            case "RE", "RUNTIME_ERROR" -> "RUNTIME_ERROR";
            case "WA", "WRONG_ANSWER" -> "WRONG_ANSWER";
            case "TLE", "TIME_LIMIT", "TIME_LIMIT_EXCEEDED" -> "TIME_LIMIT_EXCEEDED";
            case "MLE", "MEMORY_LIMIT", "MEMORY_LIMIT_EXCEEDED" -> "MEMORY_LIMIT_EXCEEDED";
            case "SE", "SEGMENTATION_ERROR", "SEGMENTATION_FAULT" -> "SEGMENTATION_FAULT";
            default -> status;
        };
    }

    private String ruleRootCause(String type) {
        return switch (type) {
            case "COMPILE_ERROR" -> "提交未通过编译，优先检查语法、头文件、类型和接口声明。";
            case "RUNTIME_ERROR" -> "程序运行阶段发生异常，优先检查空指针、数组越界、非法输入和递归边界。";
            case "WRONG_ANSWER" -> "程序能够运行但输出与判题结果不一致，优先核对边界条件、状态转移和输出格式。";
            case "TIME_LIMIT_EXCEEDED" -> "程序运行时间超过限制，优先检查算法复杂度和重复计算。";
            case "MEMORY_LIMIT_EXCEEDED" -> "程序使用内存超过限制，优先检查大对象、数组规模和不必要的数据副本。";
            case "SEGMENTATION_FAULT" -> "程序访问了无效内存，优先检查指针、数组下标和对象生命周期。";
            default -> "当前提交记录没有可进一步细化的错误类型，需结合判题详情继续定位。";
        };
    }

    private String ruleTopic(String type) {
        return switch (type) {
            case "COMPILE_ERROR" -> "编译与语法";
            case "RUNTIME_ERROR", "SEGMENTATION_FAULT" -> "运行时安全与边界";
            case "WRONG_ANSWER" -> "测试用例与边界条件";
            case "TIME_LIMIT_EXCEEDED" -> "算法复杂度";
            case "MEMORY_LIMIT_EXCEEDED" -> "空间复杂度";
            default -> "判题结果核对";
        };
    }

    private String ruleResource(String type) {
        return switch (type) {
            case "COMPILE_ERROR" -> "回看编译器报错位置，逐条修复后重新提交。";
            case "RUNTIME_ERROR", "SEGMENTATION_FAULT" -> "用最小输入复现异常，逐步检查边界和变量生命周期。";
            case "WRONG_ANSWER" -> "补充空输入、最小值、重复值和极端值测试。";
            case "TIME_LIMIT_EXCEEDED" -> "先估算当前复杂度，再比较是否存在重复遍历或可替换的数据结构。";
            case "MEMORY_LIMIT_EXCEEDED" -> "检查容器容量、缓存范围和是否可以流式处理数据。";
            default -> "查看 PTA 判题详情后再进行针对性修改。";
        };
    }

    private List<String> ruleSuggestions(String type) {
        return List.of(ruleResource(type));
    }

    private String buildRuleOverallAssessment(
            boolean allAccepted,
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> categories) {
        if (allAccepted) {
            return "根据当前真实提交记录，所有可识别的判题结果均为通过。本次展示基于判题结果，不冒充 AI 分析。";
        }
        if (attempts.isEmpty()) {
            return "存在提交记录，但当前没有足够的判题证据生成详细分析。";
        }
        if (categories.isEmpty()) {
            return "已读取真实提交记录，但判题服务未提供可识别的错误类型，请查看 PTA 详情后重试。";
        }
        return "AI 错误分析服务当前不可用，以下内容根据真实提交记录中的判题状态统计生成，仅作为规则兜底参考。";
    }

    private Map<String, Object> normalizeErrorAnalysisResult(
            Map<String, Object> responseBody,
            List<StudentSubmissionAttempt> attempts) {
        if (responseBody == null) return null;
        Map<String, Object> data = extractDataMap(responseBody);
        if (data == null || data.isEmpty()) return responseBody;

        String mode = normalizeGenerationMode(data.get("generationMode"));
        if (mode == null) {
            if (Boolean.TRUE.equals(data.get("aiGenerated"))) {
                mode = "AI_MODEL";
            } else if (data.containsKey("aiGenerated") && Boolean.FALSE.equals(data.get("aiGenerated"))) {
                mode = isAllAcceptedResult(data, attempts) ? "JUDGE_RESULT" : "RULE_FALLBACK";
            } else {
                mode = isAllAcceptedResult(data, attempts) ? "JUDGE_RESULT" : "AI_MODEL";
            }
        }

        data.put("generationMode", mode);
        data.put("aiGenerated", "AI_MODEL".equals(mode));
        data.putIfAbsent("provider", "AI_MODEL".equals(mode) ? "error-analysis-service" : "system");
        if ("RULE_FALLBACK".equals(mode)) {
            data.putIfAbsent("fallbackReason", "AI_SERVICE_RULE_FALLBACK");
        } else if ("JUDGE_RESULT".equals(mode)) {
            data.putIfAbsent("fallbackReason", "JUDGE_RESULT_ONLY");
        }
        if (attempts != null) {
            normalizeProblemAnalyses(data, attempts, mode);
        }
        return responseBody;
    }

    private boolean hasProblemAnalyses(Map<String, Object> responseBody) {
        Map<String, Object> data = extractDataMap(responseBody);
        return data != null
                && data.get("problemAnalyses") instanceof List<?> analyses
                && !analyses.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void normalizeProblemAnalyses(
            Map<String, Object> data,
            List<StudentSubmissionAttempt> attempts,
            String defaultMode) {
        Map<Long, List<StudentSubmissionAttempt>> attemptsByProblem = groupAttemptsByProblemId(attempts);
        if (attemptsByProblem.isEmpty()) {
            data.put("problemAnalyses", new ArrayList<>());
            return;
        }

        Map<Long, Map<String, Object>> providedByProblem = new LinkedHashMap<>();
        Object rawAnalyses = data.get("problemAnalyses");
        if (rawAnalyses instanceof List<?> analyses) {
            for (Object item : analyses) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> analysis = new LinkedHashMap<>((Map<String, Object>) raw);
                Long problemId = parseProblemId(analysis.get("problemId"));
                List<StudentSubmissionAttempt> problemAttempts = attemptsByProblem.get(problemId);
                if (problemId == null || problemAttempts == null || providedByProblem.containsKey(problemId)) continue;

                StudentSubmissionAttempt latest = latestAttempt(problemAttempts);
                String mode = normalizeGenerationMode(analysis.get("generationMode"));
                if (mode == null) mode = defaultMode;
                analysis.put("problemId", problemId);
                analysis.put("problemTitle", resolveProblemTitle(problemAttempts));
                analysis.putIfAbsent("severity", "MEDIUM");
                analysis.putIfAbsent("overallAssessment", "当前题目暂无可用的详细分析。");
                analysis.putIfAbsent("errorCategories", new ArrayList<>());
                analysis.putIfAbsent("learningSuggestions", new ArrayList<>());
                analysis.put("latestCode", latest != null ? cleanCode(latest.getCode()) : null);
                analysis.put("latestJudgeStatus", latest != null
                        ? normalizeJudgeStatus(latest.getJudgeStatus()) : null);
                analysis.put("generationMode", mode);
                analysis.put("aiGenerated", "AI_MODEL".equals(mode));
                analysis.putIfAbsent("provider", "AI_MODEL".equals(mode)
                        ? safeString(data.get("provider"), "error-analysis-service") : "system");
                normalizeErrorCategories(analysis);
                providedByProblem.put(problemId, analysis);
            }
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map.Entry<Long, List<StudentSubmissionAttempt>> entry : attemptsByProblem.entrySet()) {
            Map<String, Object> analysis = providedByProblem.get(entry.getKey());
            if (analysis == null) {
                analysis = buildRuleProblemAnalysis(entry.getKey(), entry.getValue());
            }
            normalized.add(analysis);
        }
        data.put("problemAnalyses", normalized);
    }

    private Map<Long, List<StudentSubmissionAttempt>> groupAttemptsByProblemId(
            List<StudentSubmissionAttempt> attempts) {
        Map<Long, List<StudentSubmissionAttempt>> grouped = new LinkedHashMap<>();
        if (attempts == null) return grouped;
        for (StudentSubmissionAttempt attempt : attempts) {
            if (attempt == null || attempt.getProblemId() == null) continue;
            grouped.computeIfAbsent(attempt.getProblemId(), ignored -> new ArrayList<>()).add(attempt);
        }
        return grouped;
    }

    private List<Map<String, Object>> buildRuleProblemAnalyses(
            List<StudentSubmissionAttempt> attempts) {
        List<Map<String, Object>> analyses = new ArrayList<>();
        for (Map.Entry<Long, List<StudentSubmissionAttempt>> entry : groupAttemptsByProblemId(attempts).entrySet()) {
            analyses.add(buildRuleProblemAnalysis(entry.getKey(), entry.getValue()));
        }
        return analyses;
    }

    private Map<String, Object> buildRuleProblemAnalysis(
            Long problemId,
            List<StudentSubmissionAttempt> problemAttempts) {
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        for (StudentSubmissionAttempt attempt : problemAttempts) {
            if (attempt == null) continue;
            String status = normalizeJudgeStatus(attempt.getJudgeStatus());
            if (!isAcceptedStatus(status)) errorCounts.merge(status, 1, Integer::sum);
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue();
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("type", type);
            category.put("count", count);
            category.put("isSystemic", count >= 2);
            category.put("rootCause", ruleRootCause(type));
            category.put("specificIssues", List.of(resolveProblemTitle(problemAttempts)));
            category.put("suggestions", ruleSuggestions(type));
            categories.add(category);

            Map<String, Object> suggestion = new LinkedHashMap<>();
            suggestion.put("priority", count >= 2 ? "HIGH" : "MEDIUM");
            suggestion.put("topic", ruleTopic(type));
            suggestion.put("reason", "本题真实判题记录中出现 " + count + " 次" + type);
            suggestion.put("suggestedResources", ruleResource(type));
            suggestions.add(suggestion);
        }

        boolean allAccepted = !problemAttempts.isEmpty() && errorCounts.isEmpty()
                && problemAttempts.stream().allMatch(attempt ->
                attempt != null && isAcceptedStatus(attempt.getJudgeStatus()));
        StudentSubmissionAttempt latest = latestAttempt(problemAttempts);
        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("problemId", problemId);
        analysis.put("problemTitle", resolveProblemTitle(problemAttempts));
        analysis.put("severity", categories.isEmpty() ? "LOW"
                : categories.stream().anyMatch(category ->
                List.of("COMPILE_ERROR", "RUNTIME_ERROR", "SEGMENTATION_FAULT")
                        .contains(category.get("type")) || Boolean.TRUE.equals(category.get("isSystemic")))
                ? "HIGH" : "MEDIUM");
        analysis.put("overallAssessment", allAccepted
                ? "根据本题当前真实提交记录，所有可识别的判题结果均为通过。"
                : "本题 AI 详细分析暂不可用，以下内容仅根据本题真实判题状态生成。");
        analysis.put("errorCategories", categories);
        analysis.put("learningSuggestions", suggestions);
        analysis.put("latestCode", latest != null ? cleanCode(latest.getCode()) : null);
        analysis.put("latestJudgeStatus", latest != null
                ? normalizeJudgeStatus(latest.getJudgeStatus()) : null);
        analysis.put("generationMode", allAccepted ? "JUDGE_RESULT" : "RULE_FALLBACK");
        analysis.put("aiGenerated", false);
        analysis.put("provider", "system");
        analysis.put("fallbackReason", allAccepted
                ? "JUDGE_RESULT_ONLY" : "AI_PROBLEM_ANALYSIS_UNAVAILABLE");
        return analysis;
    }

    private Long parseProblemId(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMap(Map<String, Object> responseBody) {
        if (responseBody == null) return null;
        Object data = responseBody.get("data");
        if (data instanceof Map<?, ?>) {
            return (Map<String, Object>) data;
        }
        return responseBody;
    }

    private void applyStoredGenerationMetadata(
            Map<String, Object> data,
            Map<String, Object> rawData,
            AiErrorAnalysisReport report) {
        String mode = normalizeGenerationMode(rawData != null ? rawData.get("generationMode") : null);
        if (mode == null) {
            if (rawData != null && Boolean.TRUE.equals(rawData.get("aiGenerated"))) {
                mode = "AI_MODEL";
            } else if (Boolean.TRUE.equals(report.getAiGenerated())) {
                mode = "AI_MODEL";
            } else if ("ERROR".equalsIgnoreCase(report.getReportType()) && rawData != null
                    && !rawData.containsKey("aiGenerated")) {
                mode = "AI_MODEL";
            } else {
                mode = "RULE_FALLBACK";
            }
        }

        data.put("generationMode", mode);
        data.put("aiGenerated", "AI_MODEL".equals(mode));
        Object provider = rawData != null ? rawData.get("provider") : null;
        data.put("provider", safeString(provider, "AI_MODEL".equals(mode) ? "error-analysis-service" : "system"));
        Object fallbackReason = rawData != null ? rawData.get("fallbackReason") : null;
        String reason = safeString(fallbackReason, null);
        if (reason != null) {
            data.put("fallbackReason", reason);
        } else if ("RULE_FALLBACK".equals(mode)) {
            data.put("fallbackReason", "AI_SERVICE_RULE_FALLBACK");
        }
    }

    private String normalizeGenerationMode(Object rawMode) {
        String mode = safeString(rawMode, null);
        if (mode == null) return null;
        mode = mode.toUpperCase();
        return switch (mode) {
            case "AI_MODEL", "RULE_FALLBACK", "JUDGE_RESULT" -> mode;
            default -> null;
        };
    }

    private boolean isAllAcceptedResult(Map<String, Object> data, List<StudentSubmissionAttempt> attempts) {
        Object categories = data.get("errorCategories");
        if (categories instanceof List<?> list && !list.isEmpty()) return false;

        if (attempts != null && !attempts.isEmpty()) {
            for (StudentSubmissionAttempt attempt : attempts) {
                String status = attempt.getJudgeStatus();
                if (!isAcceptedStatus(status)) return false;
            }
            return true;
        }

        return isAcceptedStatus(safeString(data.get("latestJudgeStatus"), null));
    }

    private boolean isAcceptedStatus(String status) {
        return "ACCEPTED".equals(normalizeJudgeStatus(status));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
