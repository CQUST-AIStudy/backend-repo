package com.tap.backend.academic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.academic.dao.AiErrorAnalysisReportDao;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.entity.AiErrorAnalysisReport;
import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.StudentSubmissionAttempt;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private static final int AI_ERROR_MAX_ATTEMPTS = 24;
    private static final int AI_ERROR_MAX_CODE_CHARS = 1200;
    private static final int AI_ERROR_MAX_ERROR_MESSAGE_CHARS = 500;
    private static final int AI_ERROR_MAX_STATEMENT_CHARS = 1800;
    private static final int AI_ERROR_MAX_OUTPUT_TOKENS = 6000;
    // 分题深度解析（按需单题调 AI，节省 token）
    private static final String PROBLEM_DEEP_REPORT_TYPE = "ERROR_PROBLEM";
    private static final int PROBLEM_DEEP_MAX_CODE_CHARS = 8000;
    private static final int PROBLEM_DEEP_MAX_STATEMENT_CHARS = 4000;
    private static final int PROBLEM_DEEP_MAX_OUTPUT_TOKENS = 12000;
    private static final long PROBLEM_DEEP_CACHE_HOURS = 24;

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

    /** 分题深度解析后台生成防重集合：并发点击/轮询不重复调 AI */
    private final java.util.Set<String> pendingProblemDeep = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Autowired
    @Qualifier("aiExecutor")
    private Executor aiExecutor;

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
            List<TeacherSubmissionProblemRow> problemRows =
                    findProblemRowsForAnalysis(studentNo, experimentId);
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
                        experimentId, experimentName, attempts, submissions, problemRows);
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
        return analyzeErrorFromDb(studentNo, studentName, experimentId, forceRefresh, false);
    }

    /**
     * @param skipAi true 时只返回提交快照与已存储的分题深度解析，不触发任何 AI 调用（节省 token，分题按需生成）
     */
    public Map<String, Object> analyzeErrorFromDb(String studentNo, String studentName,
                                                   int experimentId, boolean forceRefresh, boolean skipAi) {
        List<TeacherSubmissionProblemRow> problemRows = findProblemRowsForAnalysis(studentNo, experimentId);
        // ── 缓存命中 ──
        if (!forceRefresh && !skipAi) {
            Map<String, Object> cached = getSyncCache(studentNo, experimentId, "error");
            if (cached != null && cacheCoversCurrentProblems(cached, problemRows)) {
                logger.info("analyzeErrorFromDb: cache hit for student={}, experiment={}", studentNo, experimentId);
                return normalizeErrorAnalysisResult(cached, loadAttemptsForNormalization(studentNo, experimentId));
            } else if (cached != null) {
                logger.info("analyzeErrorFromDb: cached result does not cover current problems; rebuilding for student={}, experiment={}",
                        studentNo, experimentId);
            }
        }

        List<StudentSubmissionAttempt> attempts = loadAnalysisAttempts(studentNo, experimentId);
        if (skipAi) {
            return buildSubmissionSnapshotResult(studentNo, experimentId, attempts);
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
                    experiment != null ? experiment.getName() : ("实验" + experimentId),
                    attempts, submissions, problemRows);
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

    // ==================== 分题按需深度解析（节省 token） ====================

    /**
     * 加载学生该实验的完整提交记录：
     * primary → raw 合并 → 当前题目状态补齐 → 多题代码拆分 → code-only 兜底。
     */
    private List<StudentSubmissionAttempt> loadAnalysisAttempts(String studentNo, int experimentId) {
        List<TeacherSubmissionProblemRow> problemRows = findProblemRowsForAnalysis(studentNo, experimentId);
        List<StudentSubmissionAttempt> primaryAttempts = teacherExperimentQueryDao
                .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
        List<StudentSubmissionAttempt> attempts = primaryAttempts == null
                ? new ArrayList<>() : new ArrayList<>(primaryAttempts);
        mergeRawAttempts(attempts, findRawAttemptsForAnalysis(studentNo, experimentId));
        appendCurrentProblemStates(attempts, problemRows);
        // ── 通用优化：raw 数据中若代码含多题标记，拆分为每题独立提交 ──
        if (!attempts.isEmpty()) {
            String firstCode = attempts.get(0).getCode();
            if (firstCode != null && firstCode.contains("第") && firstCode.contains("题")) {
                String codeBlob = fetchCodeOnly(studentNo, experimentId);
                if (codeBlob != null && !codeBlob.isBlank()) {
                    List<String> chunks = splitCodePerProblem(codeBlob);
                    if (chunks.size() > 1) {
                        logger.info("loadAnalysisAttempts: split code blob into {} per-problem submissions for student={}, experiment={}",
                                chunks.size(), studentNo, experimentId);
                        attempts = buildAttemptsFromCode(codeBlob, experimentId);
                    }
                }
            }
        }
        if (attempts.isEmpty()) {
            // ── second fallback: student_code 表有代码但无判题记录 ──
            String codeOnly = fetchCodeOnly(studentNo, experimentId);
            if (codeOnly != null && !codeOnly.isBlank()) {
                logger.info("loadAnalysisAttempts: code-only fallback for student={}, experiment={}",
                        studentNo, experimentId);
                attempts = buildAttemptsFromCode(codeOnly, experimentId);
            }
        }
        return attempts;
    }

    /**
     * 快照模式：不触发任何 AI 调用，只返回每题提交状态 + 已落库的分题深度解析，
     * 供前端按题点击"生成深度解析"时再单独调 AI（节省 token）。
     */
    private Map<String, Object> buildSubmissionSnapshotResult(
            String studentNo, int experimentId, List<StudentSubmissionAttempt> attempts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generationMode", "SNAPSHOT");
        result.put("aiGenerated", false);
        result.put("provider", "system");
        result.put("overallAssessment", "已加载提交快照，可点击各题的\"生成AI深度解析\"按钮按需生成。");
        result.put("errorCategories", new ArrayList<>());
        result.put("learningSuggestions", new ArrayList<>());

        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map.Entry<Long, List<StudentSubmissionAttempt>> entry
                : groupAttemptsByProblemId(attempts).entrySet()) {
            List<StudentSubmissionAttempt> problemAttempts = entry.getValue();
            StudentSubmissionAttempt latest = latestAttempt(problemAttempts);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("problemId", entry.getKey());
            summary.put("problemTitle", resolveProblemTitle(problemAttempts));
            summary.put("submissionCount", problemAttempts.size());
            summary.put("latestCode", latest != null ? cleanCode(latest.getCode()) : null);
            summary.put("latestJudgeStatus", latest != null
                    ? normalizeJudgeStatus(latest.getJudgeStatus()) : null);
            summary.put("generationMode", "SNAPSHOT");
            summary.put("aiGenerated", false);
            summary.put("provider", "system");
            summaries.add(summary);
        }
        result.put("problemAnalyses", summaries);
        // 历史落库可能含规则 mock 数据：只下发 AI 生成/NO_CODE 的真实结果
        Map<Long, Map<String, Object>> deepUsable = new LinkedHashMap<>();
        findStoredProblemDeepAnalyses(studentNo, experimentId).forEach((pid, a) -> {
            if (isProblemDeepResultUsable(a)) deepUsable.put(pid, a);
        });
        result.put("problemDeepAnalyses", deepUsable);
        StudentSubmissionAttempt overallLatest = attempts != null ? latestAttempt(attempts) : null;
        result.put("latestCode", overallLatest != null ? cleanCode(overallLatest.getCode()) : null);
        result.put("latestJudgeStatus", overallLatest != null
                ? normalizeJudgeStatus(overallLatest.getJudgeStatus()) : null);
        return result;
    }

    /**
     * 单题深度解析：真实调用 AI 对题面 + 完整代码（带行号）+ 判题历史做详细解析。
     * <ul>
     *   <li>Redis 缓存 24h + DB 落库（reportType=ERROR_PROBLEM，analysisId=errprob-{problemId}）；</li>
     *   <li>forceRefresh=true 时跳过缓存重新生成并覆盖落库；</li>
     *   <li>AI 不可用时降级为规则分析；该题无代码时返回友好提示。</li>
     * </ul>
     */
    /**
     * 分题深度解析非阻塞入口：缓存/DB 命中直接返回最终结果；
     * 未命中则启动后台生成并返回 null（调用方应答 PROCESSING，前端轮询）。
     * 外层代理 60s 超时，同步 AI 深度解析常超时 504，故改为“立即返回 + 后台回填 + 前端轮询”。
     */
    public Map<String, Object> getOrStartProblemDeep(String studentNo, String studentName,
                                                     int experimentId, long problemId, boolean forceRefresh) {
        if (!forceRefresh) {
            Map<String, Object> cached = getProblemDeepCache(studentNo, experimentId, problemId);
            if (isProblemDeepResultUsable(cached)) return cached;
            Map<String, Object> stored = findStoredProblemDeepAnalyses(studentNo, experimentId).get(problemId);
            if (isProblemDeepResultUsable(stored)) {
                putProblemDeepCache(studentNo, experimentId, problemId, stored);
                return stored;
            }
            // 失败冷却期内不再重复调 AI，直接答 FAILED，前端停止轮询并提示
            if (isProblemDeepFailedRecently(studentNo, experimentId, problemId)) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("status", "FAILED");
                failed.put("problemId", problemId);
                failed.put("message", "深度解析生成失败（AI 服务不可用或返回异常），请稍后重试");
                return failed;
            }
        }
        String key = studentNo + ":" + experimentId + ":" + problemId;
        if (pendingProblemDeep.add(key)) {
            aiExecutor.execute(() -> {
                try {
                    if (analyzeProblemDeep(studentNo, studentName, experimentId, problemId, true) == null) {
                        markProblemDeepFailed(studentNo, experimentId, problemId);
                    }
                } catch (Exception e) {
                    logger.warn("background problem deep analysis failed: student={}, problem={}, err={}",
                            studentNo, problemId, e.getMessage());
                    markProblemDeepFailed(studentNo, experimentId, problemId);
                } finally {
                    pendingProblemDeep.remove(key);
                }
            });
        }
        return null;
    }

    public Map<String, Object> analyzeProblemDeep(String studentNo, String studentName,
                                                  int experimentId, long problemId, boolean forceRefresh) {
        if (!forceRefresh) {
            Map<String, Object> cached = getProblemDeepCache(studentNo, experimentId, problemId);
            if (isProblemDeepResultUsable(cached)) {
                logger.info("analyzeProblemDeep: cache hit student={}, experiment={}, problem={}",
                        studentNo, experimentId, problemId);
                return cached;
            }
            Map<String, Object> stored = findStoredProblemDeepAnalyses(studentNo, experimentId).get(problemId);
            if (isProblemDeepResultUsable(stored)) {
                putProblemDeepCache(studentNo, experimentId, problemId, stored);
                return stored;
            }
        }

        List<StudentSubmissionAttempt> attempts = loadAnalysisAttempts(studentNo, experimentId);
        List<StudentSubmissionAttempt> problemAttempts = attempts.stream()
                .filter(a -> a != null && a.getProblemId() != null && a.getProblemId() == problemId)
                .collect(Collectors.toList());
        StudentSubmissionAttempt latest = latestAttempt(problemAttempts);
        String latestCode = latest != null ? cleanCode(latest.getCode()) : "";
        if (latestCode == null || latestCode.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("problemId", problemId);
            empty.put("problemTitle", resolveProblemTitle(problemAttempts));
            empty.put("generationMode", "NO_CODE");
            empty.put("aiGenerated", false);
            empty.put("provider", "system");
            empty.put("fallbackReason", "NO_CODE_FOR_PROBLEM");
            empty.put("overallAssessment", "本题暂未找到可分析的代码，请先在PTA平台提交代码后再生成深度解析。");
            return empty;
        }

        TeacherSubmissionProblemRow problemRow = findProblemRowForProblem(studentNo, experimentId, problemId);
        String experimentName = resolveExperimentName(experimentId);
        Map<String, Object> analysis = null;
        boolean aiGenerated = false;
        if (isRealAiProviderAvailable()) {
            try {
                String prompt = buildProblemDeepAnalysisPrompt(studentNo, studentName, experimentId,
                        experimentName, problemId, problemRow, problemAttempts, latestCode);
                String raw = aiProvider.chatJson(prompt, null, PROBLEM_DEEP_MAX_OUTPUT_TOKENS);
                try {
                    analysis = parseModelJsonObject(raw);
                } catch (JsonProcessingException firstError) {
                    logger.warn("analyzeProblemDeep: JSON incomplete, retrying compact: student={}, problem={}",
                            studentNo, problemId);
                    raw = aiProvider.chatJson(prompt + """

                            请重新输出完整JSON，并压缩内容：detailedAnalysis不超过600个汉字，fixPlan与testCases各不超过4条，knowledgePoints不超过5条。
                            """, null, PROBLEM_DEEP_MAX_OUTPUT_TOKENS);
                    analysis = parseModelJsonObject(raw);
                }
                analysis = extractDataMap(analysis);
                aiGenerated = analysis != null && hasUsableProblemDeepAnalysis(analysis);
            } catch (Exception e) {
                logger.warn("analyzeProblemDeep: AI provider failed: student={}, problem={}, error={}",
                        studentNo, problemId, e.getMessage());
                analysis = null;
            }
        }
        if (!aiGenerated) {
            // 按需求不做规则兜底：生成失败就是失败，不写 mock 内容入缓存/DB
            logger.warn("analyzeProblemDeep: AI generation failed, return null (no mock fallback): student={}, problem={}",
                    studentNo, problemId);
            return null;
        }

        // ── 回填元数据（前端展示与落库） ──
        analysis.put("problemId", problemId);
        analysis.putIfAbsent("problemTitle", resolveProblemTitle(problemAttempts));
        analysis.putIfAbsent("severity", "MEDIUM");
        analysis.put("latestCode", latestCode);
        analysis.put("latestJudgeStatus", latest.getJudgeStatus() != null
                ? normalizeJudgeStatus(latest.getJudgeStatus()) : null);
        if (aiGenerated) {
            analysis.put("generationMode", "AI_MODEL");
            analysis.put("aiGenerated", true);
            analysis.put("provider", aiProvider.name());
            analysis.put("model", aiProvider.model());
        }
        normalizeErrorCategories(analysis);

        saveProblemDeepReport(studentNo, experimentId, experimentName, problemId, analysis);
        putProblemDeepCache(studentNo, experimentId, problemId, analysis);
        logger.info("analyzeProblemDeep completed: student={}, experiment={}, problem={}, aiGenerated={}",
                studentNo, experimentId, problemId, aiGenerated);
        return analysis;
    }

    private TeacherSubmissionProblemRow findProblemRowForProblem(
            String studentNo, int experimentId, long problemId) {
        for (TeacherSubmissionProblemRow row : findProblemRowsForAnalysis(studentNo, experimentId)) {
            if (row != null && row.getProblemId() != null && row.getProblemId() == problemId) {
                return row;
            }
        }
        return null;
    }

    /** 读取已落库的分题深度解析（reportType=ERROR_PROBLEM），按 problemId 组 map。 */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> findStoredProblemDeepAnalyses(
            String studentNo, int experimentId) {
        Map<Long, Map<String, Object>> byProblem = new LinkedHashMap<>();
        try {
            List<AiErrorAnalysisReport> reports = reportDao.findByStudentAndExperiment(studentNo, experimentId);
            if (reports == null) return byProblem;
            for (AiErrorAnalysisReport report : reports) {
                if (report == null || !PROBLEM_DEEP_REPORT_TYPE.equals(report.getReportType())) continue;
                String rawJson = report.getRawResponseJson();
                if (rawJson == null || rawJson.isBlank()) continue;
                Map<String, Object> data;
                try {
                    data = extractDataMap(objectMapper.readValue(rawJson, Map.class));
                } catch (Exception e) {
                    logger.debug("Skip unreadable stored problem deep report: analysisId={}",
                            report.getAnalysisId());
                    continue;
                }
                if (data == null || data.isEmpty()) continue;
                Long problemId = parseProblemId(data.get("problemId"));
                if (problemId == null) continue;
                byProblem.put(problemId, data);
            }
        } catch (Exception e) {
            logger.warn("Failed to load stored problem deep analyses: student={}, experiment={}, error={}",
                    studentNo, experimentId, e.getMessage());
        }
        return byProblem;
    }

    /** 分题深度解析落库：先删同题旧记录再写入（analysisId = errprob-{problemId}）。 */
    private void saveProblemDeepReport(String studentNo, int experimentId, String experimentName,
                                       long problemId, Map<String, Object> analysis) {
        try {
            String analysisId = "errprob-" + problemId;
            analysis.put("analysisId", analysisId);
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("data", analysis);
            reportDao.deleteByStudentExperimentTypeAndAnalysisId(
                    studentNo, experimentId, PROBLEM_DEEP_REPORT_TYPE, analysisId);
            saveReport(studentNo, experimentId, experimentName, PROBLEM_DEEP_REPORT_TYPE, wrapper);
        } catch (Exception e) {
            logger.warn("Failed to save problem deep report: student={}, experiment={}, problem={}, error={}",
                    studentNo, experimentId, problemId, e.getMessage());
        }
    }

    private String problemDeepCacheKey(String studentNo, int experimentId, long problemId) {
        return REDIS_SYNC_KEY_PREFIX + studentNo + ":" + experimentId + ":error:problem:" + problemId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getProblemDeepCache(String studentNo, int experimentId, long problemId) {
        if (redisTemplate == null) return null;
        try {
            String json = redisTemplate.opsForValue()
                    .get(problemDeepCacheKey(studentNo, experimentId, problemId));
            if (json == null || json.isEmpty()) return null;
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.debug("Problem deep cache miss: {}/{}/{}", studentNo, experimentId, problemId);
            return null;
        }
    }

    private void putProblemDeepCache(String studentNo, int experimentId, long problemId,
                                     Map<String, Object> analysis) {
        if (redisTemplate == null || analysis == null || analysis.isEmpty()) return;
        try {
            redisTemplate.opsForValue().set(problemDeepCacheKey(studentNo, experimentId, problemId),
                    objectMapper.writeValueAsString(analysis),
                    PROBLEM_DEEP_CACHE_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            logger.warn("Problem deep cache write failed: {}", e.getMessage());
        }
    }

    private boolean hasUsableProblemDeepAnalysis(Map<String, Object> analysis) {
        if (analysis == null || analysis.isEmpty()) return false;
        String overall = safeString(analysis.get("overallAssessment"), "").trim();
        String detailed = safeString(analysis.get("detailedAnalysis"), "").trim();
        return overall.length() >= 10 || detailed.length() >= 20;
    }

    /** 只有 AI 生成或 NO_CODE 事实结果才算可用命中；历史规则 mock 数据不再当作深度解析 */
    private boolean isProblemDeepResultUsable(Map<String, Object> analysis) {
        if (analysis == null || analysis.isEmpty()) return false;
        if ("NO_CODE".equals(analysis.get("generationMode"))) return true;
        return Boolean.TRUE.equals(analysis.get("aiGenerated"));
    }

    private String problemDeepFailKey(String studentNo, int experimentId, long problemId) {
        return REDIS_KEY_PREFIX + "problemdeep:fail:" + studentNo + ":" + experimentId + ":" + problemId;
    }

    /** 失败冷却 5 分钟：避免轮询反复触发 AI 调用 */
    private boolean isProblemDeepFailedRecently(String studentNo, int experimentId, long problemId) {
        if (redisTemplate == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(problemDeepFailKey(studentNo, experimentId, problemId)));
        } catch (Exception e) {
            return false;
        }
    }

    private void markProblemDeepFailed(String studentNo, int experimentId, long problemId) {
        if (redisTemplate == null) return;
        try {
            redisTemplate.opsForValue().set(problemDeepFailKey(studentNo, experimentId, problemId),
                    "1", 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.warn("Problem deep fail marker write failed: {}", e.getMessage());
        }
    }

    /** 构建单题深度解析 prompt：题面 + 判题历史 + 带行号完整代码。 */
    private String buildProblemDeepAnalysisPrompt(
            String studentNo, String studentName, int experimentId, String experimentName,
            long problemId,
            TeacherSubmissionProblemRow problemRow,
            List<StudentSubmissionAttempt> problemAttempts,
            String latestCode) {
        StringBuilder history = new StringBuilder();
        int index = 1;
        for (StudentSubmissionAttempt attempt : problemAttempts) {
            if (attempt == null) continue;
            history.append(index++).append(". 状态=")
                    .append(normalizeJudgeStatus(attempt.getJudgeStatus()));
            if (attempt.getScore() != null) {
                history.append(" 得分=").append(attempt.getScore());
            }
            String errorMessage = buildErrorMessage(attempt);
            if (errorMessage != null && !errorMessage.isBlank()) {
                history.append(" 错误信息=").append(limitText(errorMessage, 400));
            }
            history.append('\n');
        }
        String statement = problemRow != null
                ? limitText(safeString(problemRow.getStatementMd(), ""), PROBLEM_DEEP_MAX_STATEMENT_CHARS)
                : "";
        String problemTitle = problemRow != null
                ? safeString(problemRow.getProblemTitle(), "")
                : resolveProblemTitle(problemAttempts);
        return """
                你是高校程序设计课程的资深助教。请针对下面这一道题，结合真实题面、学生完整代码和真实判题历史，做一次深入、具体的解析。禁止编造测试数据、期望输出或分数。

                分析要求：
                1. problemUnderstanding：用2~4句概括题目的核心要求、输入输出格式与关键约束/边界条件。
                2. approachReview：点评学生代码的整体思路与算法是否正确、是否满足题目复杂度要求，指出思路层面的优缺点。
                3. detailedAnalysis：深入分析代码（必须引用具体行号或表达式），涵盖：正确性（逻辑与题面要求是否一致）、边界与异常输入处理、复杂度、代码风格与潜在隐患；若存在失败判题记录，必须结合错误信息定位根因：能由错误信息直接证明时用"已确认："开头，否则用"待验证："开头并写明缺少的证据与最小验证方法。若全部通过，则分析代码质量、潜在风险与可优化点。
                4. fixPlan：按顺序给出2~5条可执行的改进步骤（至少1条代码级修改）；全部通过时可给优化建议。
                5. testCases：给出2~4个学生可自行验证的测试用例（输入+预期输出+验证目的），不得编造OJ官方测试点。
                6. knowledgePoints：本题涉及的2~5个知识点。
                7. learningSuggestions：1~3条，含priority/topic/reason/suggestedResources。
                8. overallAssessment：不超过200个汉字，总结本题完成质量与下一步重点。

                输出必须是严格 JSON，不要 Markdown 代码块，不要解释。JSON schema：
                {
                  "severity": "HIGH|MEDIUM|LOW",
                  "overallAssessment": "...",
                  "problemUnderstanding": "...",
                  "approachReview": "...",
                  "detailedAnalysis": "...(600~1000个汉字，引用行号，分点论述)",
                  "errorCategories": [
                    {"type": "COMPILE_ERROR|RUNTIME_ERROR|WRONG_ANSWER|TIME_LIMIT_EXCEEDED|MEMORY_LIMIT_EXCEEDED|SEGMENTATION_FAULT|UNKNOWN", "count": 1, "isSystemic": false, "rootCause": "...", "specificIssues": ["..."], "suggestions": ["..."]}
                  ],
                  "fixPlan": ["..."],
                  "testCases": [{"input": "...", "expectedOutput": "...", "purpose": "..."}],
                  "knowledgePoints": ["..."],
                  "learningSuggestions": [
                    {"priority": "HIGH|MEDIUM|LOW", "topic": "...", "reason": "...", "suggestedResources": "..."}
                  ]
                }

                学生：%s（%s）
                实验：%s（%d）
                题目：%s（problemId=%d）
                题面（Markdown）：
                %s

                判题历史（按提交顺序）：
                %s

                学生最新完整代码（带行号）：
                %s
                """.formatted(
                safeString(studentName, studentNo),
                safeString(studentNo, ""),
                safeString(experimentName, "实验" + experimentId),
                experimentId,
                safeString(problemTitle, "题目" + problemId),
                problemId,
                statement.isBlank() ? "（未获取到题面，请仅基于代码与判题历史分析）" : statement,
                history.length() == 0 ? "（无判题记录）" : history.toString().trim(),
                limitText(withLineNumbers(latestCode), PROBLEM_DEEP_MAX_CODE_CHARS));
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
        String diagnostic = extractRawDiagnostic(a.getRawJson());
        if (!diagnostic.isBlank()) {
            sb.append("\n判题器诊断: ").append(diagnostic);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractRawDiagnostic(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return "";
        try {
            Map<String, Object> raw = objectMapper.readValue(rawJson, Map.class);
            String[] diagnosticKeys = {
                    "errorMessage", "error_message", "compilerMessage", "compiler_message",
                    "compileError", "compile_error", "diagnostic", "detail"
            };
            for (String key : diagnosticKeys) {
                Object value = raw.get(key);
                if (value instanceof String message && !message.isBlank()) {
                    return limitText(message, AI_ERROR_MAX_ERROR_MESSAGE_CHARS);
                }
            }
            for (String nestedKey : List.of("result", "data", "judgeResult", "judge_result")) {
                Object nested = raw.get(nestedKey);
                if (nested instanceof Map<?, ?> nestedMap) {
                    for (String key : diagnosticKeys) {
                        Object value = nestedMap.get(key);
                        if (value instanceof String message && !message.isBlank()) {
                            return limitText(message, AI_ERROR_MAX_ERROR_MESSAGE_CHARS);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // A malformed raw row must not block the rest of the real judge evidence.
        }
        return "";
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
            List<Map<String, Object>> submissions,
            List<TeacherSubmissionProblemRow> problemRows) {
        if (!isRealAiProviderAvailable() || !hasNonAcceptedAttempt(attempts)) {
            return null;
        }

        try {
            String prompt = buildAiErrorAnalysisPrompt(studentNo, studentName,
                    experimentId, experimentName, attempts, submissions, problemRows);
            String raw = aiProvider.chatJson(prompt, null, AI_ERROR_MAX_OUTPUT_TOKENS);
            Map<String, Object> data;
            boolean retried = false;
            try {
                data = parseModelJsonObject(raw);
            } catch (JsonProcessingException firstError) {
                logger.warn("AI error analysis JSON was incomplete; retrying once with compact output: student={}, experiment={}",
                        studentNo, experimentId);
                raw = aiProvider.chatJson(buildCompactErrorAnalysisRetryPrompt(prompt), null,
                        AI_ERROR_MAX_OUTPUT_TOKENS);
                data = parseModelJsonObject(raw);
                retried = true;
            }
            data = extractDataMap(data);
            if (data == null || data.isEmpty()) return null;

            normalizeAiErrorAnalysisData(data, studentNo, studentName,
                    experimentId, experimentName, attempts, submissions);
            guardUnprovenWrongAnswerClaims(data, attempts);
            downgradeIncompleteAiProblemAnalyses(data, attempts);
            if (!hasDetailedAiProblemAnalyses(data) && !retried) {
                logger.warn("AI error analysis lacked clear evidence or mixed confirmed and uncertain claims; retrying once: student={}, experiment={}",
                        studentNo, experimentId);
                raw = aiProvider.chatJson(buildCompactErrorAnalysisRetryPrompt(prompt), null,
                        AI_ERROR_MAX_OUTPUT_TOKENS);
                data = extractDataMap(parseModelJsonObject(raw));
                if (data == null || data.isEmpty()) return null;
                normalizeAiErrorAnalysisData(data, studentNo, studentName,
                        experimentId, experimentName, attempts, submissions);
                guardUnprovenWrongAnswerClaims(data, attempts);
                downgradeIncompleteAiProblemAnalyses(data, attempts);
            }
            if (!hasDetailedAiProblemAnalyses(data)) {
                logger.warn("AI provider returned error analysis without the required evidence detail for student={}, experiment={}",
                        studentNo, experimentId);
                return null;
            }
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
            List<Map<String, Object>> submissions,
            List<TeacherSubmissionProblemRow> problemRows) {
        return """
                你是高校程序设计课程的 AI 错误分析助手。请只基于下面给出的真实题面、提交记录、判题状态和代码进行逐题诊断，不要编造分数、测试点、期望输出或实际输出。

                分析质量要求：
                1. 先核对 problemRequirement 中的题面要求，再检查同一 problemId 的代码和判题记录。
                2. overallAssessment 要直接说明“判题证据 + 代码中的具体位置/表达式 + 造成的结果”，不要只写“可能是格式、精度或边界问题”。
                3. rootCause 必须二选一：能够由编译器或运行时错误信息直接证明时，以“已确认：”开头并引用具体错误信息和代码表达式，且不得包含“可能、推测、需验证、证据不足”或问号；不能证明时，以“待验证：”开头，写明缺少的证据和最小验证方法，且不得使用“已确认”。仅有 WRONG_ANSWER、PARTIAL_ACCEPTED、得分或重复次数而没有失败输入、期望输出、实际输出时，一律使用“待验证：”，代码审查只能作为候选原因，不能宣布唯一根因。
                4. specificIssues 输出 2 至 4 条定位依据，每条说明代码位置或表达式、违反的题面要求及影响。没有真实测试点时不得伪造具体输入输出。
                5. suggestions 输出 2 至 4 个按顺序可执行的修改步骤，至少包含一条代码级修改和一条可自行构造的验证用例；不要只写“检查题目要求”。
                6. 同一道题多次出现相同状态时，要说明重复次数及其代表的问题；不同 problemId 之间严禁串题。
                7. 只为存在非通过记录的 problemId 输出 problemAnalyses；每题只保留一个最主要的 errorCategory。overallAssessment 和 rootCause 各不超过180个汉字，specificIssues 和 suggestions 每条不超过120个汉字，确保JSON完整闭合。

                输出必须是严格 JSON，不要 Markdown 代码块，不要解释。JSON schema：
                {
                  "severity": "HIGH|MEDIUM|LOW",
                  "overallAssessment": "用中文说明整个实验的主要错误表现和下一步修复重点",
                  "errorCategories": [
                    {
                      "type": "COMPILE_ERROR|RUNTIME_ERROR|WRONG_ANSWER|TIME_LIMIT_EXCEEDED|MEMORY_LIMIT_EXCEEDED|SEGMENTATION_FAULT|UNKNOWN",
                      "count": 1,
                      "isSystemic": false,
                      "rootCause": "写明已确认根因及证据；无法确认时明确说明缺少什么证据",
                      "specificIssues": ["定位依据1：代码位置/表达式 + 对应题面要求 + 影响", "定位依据2"],
                      "suggestions": ["步骤1：具体代码修改", "步骤2：用于验证修改的自测用例"]
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
                compactErrorAnalysisEvidence(attempts, submissions, problemRows));
    }

    private String compactErrorAnalysisEvidence(
            List<StudentSubmissionAttempt> attempts,
            List<Map<String, Object>> submissions,
            List<TeacherSubmissionProblemRow> problemRows) {
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
            if (isAcceptedStatus(status)) continue;
            Long problemId = attempt.getProblemId();
            if (problemId != null && rows.stream().anyMatch(row -> problemId.equals(parseProblemId(row.get("problemId"))))) {
                continue;
            }
            if (rows.size() >= AI_ERROR_MAX_ATTEMPTS) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attemptNo", i + 1);
            row.put("problemId", attempt.getProblemId());
            row.put("judgeStatus", status);
            row.put("problemTitle", problemTitle);
            row.put("score", attempt.getScore());
            row.put("runtimeMs", attempt.getRuntimeMs());
            row.put("memoryKb", attempt.getMemoryKb());
            row.put("errorMessage", limitText(buildErrorMessage(attempt), AI_ERROR_MAX_ERROR_MESSAGE_CHARS));
            row.put("codeSnippet", limitText(withLineNumbers(cleanCode(attempt.getCode())), AI_ERROR_MAX_CODE_CHARS));
            row.put("codeScope", "LATEST_PROBLEM_STATE_SNAPSHOT");
            rows.add(row);
        }
        evidence.put("statusCounts", statusCounts);
        Map<String, Object> problemSummary = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : affectedProblems.entrySet()) {
            problemSummary.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        LinkedHashSet<Long> errorProblemIds = rows.stream()
                .map(row -> parseProblemId(row.get("problemId")))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> requirements = new ArrayList<>();
        if (problemRows != null) {
            for (TeacherSubmissionProblemRow problem : problemRows) {
                if (problem == null || problem.getProblemId() == null
                        || !errorProblemIds.contains(problem.getProblemId())) continue;
                Map<String, Object> requirement = new LinkedHashMap<>();
                requirement.put("problemId", problem.getProblemId());
                requirement.put("problemTitle", safeString(problem.getProblemTitle(), ""));
                requirement.put("problemNo", safeString(problem.getProblemNo(), ""));
                requirement.put("statementMd", limitText(
                        safeString(problem.getStatementMd(), ""), AI_ERROR_MAX_STATEMENT_CHARS));
                requirements.add(requirement);
            }
        }
        evidence.put("affectedProblems", problemSummary);
        evidence.put("problemRequirements", requirements);
        evidence.put("selectedErrorAttempts", rows);
        evidence.put("selectionNote", "Only real non-accepted records and real problem statements are sent. codeSnippet is the latest problem-state snapshot and must not be described as the exact code of every historical attempt. Code and statements may be truncated to control tokens.");
        return toJson(evidence);
    }

    private String limitText(String value, int maxChars) {
        if (value == null) return "";
        String text = value.trim();
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n// ... truncated for AI prompt";
    }

    private String withLineNumbers(String code) {
        if (code == null || code.isBlank()) return "";
        String[] lines = code.split("\\R", -1);
        StringBuilder numbered = new StringBuilder(code.length() + lines.length * 8);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) numbered.append('\n');
            numbered.append(String.format("%04d | ", i + 1)).append(lines[i]);
        }
        return numbered.toString();
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

    private String buildCompactErrorAnalysisRetryPrompt(String prompt) {
        return prompt + """

                请重新输出完整JSON，并严格压缩内容：每道题只保留1个主错误分类，定位依据和修改步骤各2条，每条不超过80个汉字。
                rootCause 只能二选一：
                - 已确认：题面与代码能直接证明时使用，后文不得再出现“可能、推测、需验证、证据不足”或问号；
                - 待验证：证据不能直接证明时使用，写清缺少的证据和最小验证方法，不得使用“已确认”。
                仅有 WRONG_ANSWER 或 PARTIAL_ACCEPTED 状态而没有失败输入、期望输出和实际输出时，必须使用“待验证：”。
                """;
    }

    private boolean hasDetailedAiProblemAnalyses(Map<String, Object> data) {
        Object rawAnalyses = data.get("problemAnalyses");
        if (!(rawAnalyses instanceof List<?> analyses)) return false;
        for (Object item : analyses) {
            if (!(item instanceof Map<?, ?> analysis)) continue;
            String mode = normalizeGenerationMode(analysis.get("generationMode"));
            if (!"AI_MODEL".equals(mode)) continue;
            Object rawCategories = analysis.get("errorCategories");
            if (!(rawCategories instanceof List<?> categories)) continue;
            for (Object categoryItem : categories) {
                if (!(categoryItem instanceof Map<?, ?> category)) continue;
                String rootCause = safeString(category.get("rootCause"), "").trim();
                String type = normalizeJudgeStatus(safeString(category.get("type"), "UNKNOWN"));
                boolean confirmed = rootCause.startsWith("已确认：") || rootCause.startsWith("已确认:");
                boolean pending = rootCause.startsWith("待验证：") || rootCause.startsWith("待验证:");
                if (isDetailedAiCategory(category, rootCause, type, confirmed, pending)) return true;
            }
        }
        return false;
    }

    private boolean isDetailedAiCategory(
            Map<?, ?> category,
            String rootCause,
            String type,
            boolean confirmed,
            boolean pending) {
        if (rootCause.length() < 20 || confirmed == pending) return false;
        if ("WRONG_ANSWER".equals(type) && confirmed) return false;
        if (confirmed && containsUncertaintyMarker(rootCause)) return false;
        return category.get("specificIssues") instanceof List<?> issues && issues.size() >= 2
                && category.get("suggestions") instanceof List<?> suggestions && suggestions.size() >= 2;
    }

    @SuppressWarnings("unchecked")
    private void downgradeIncompleteAiProblemAnalyses(
            Map<String, Object> data,
            List<StudentSubmissionAttempt> attempts) {
        Object rawAnalyses = data.get("problemAnalyses");
        if (!(rawAnalyses instanceof List<?> analyses)) return;

        Map<Long, List<StudentSubmissionAttempt>> attemptsByProblem = groupAttemptsByProblemId(attempts);
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : analyses) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> analysis = new LinkedHashMap<>((Map<String, Object>) raw);
            if (!"AI_MODEL".equals(normalizeGenerationMode(analysis.get("generationMode")))) {
                normalized.add(analysis);
                continue;
            }

            if (hasDetailedAiAnalysis(analysis)) {
                normalized.add(analysis);
                continue;
            }

            Long problemId = parseProblemId(analysis.get("problemId"));
            List<StudentSubmissionAttempt> problemAttempts = attemptsByProblem.get(problemId);
            if (problemId == null || problemAttempts == null || problemAttempts.isEmpty()) {
                normalized.add(analysis);
                continue;
            }

            Map<String, Object> fallback = buildRuleProblemAnalysis(problemId, problemAttempts);
            fallback.put("fallbackReason", "AI_PROBLEM_ANALYSIS_INCOMPLETE");
            normalized.add(fallback);
        }
        data.put("problemAnalyses", normalized);
    }

    @SuppressWarnings("unchecked")
    private boolean hasDetailedAiAnalysis(Map<String, Object> analysis) {
        Object rawCategories = analysis.get("errorCategories");
        if (!(rawCategories instanceof List<?> categories)) return false;
        for (Object item : categories) {
            if (!(item instanceof Map<?, ?> rawCategory)) continue;
            Map<String, Object> category = (Map<String, Object>) rawCategory;
            String rootCause = safeString(category.get("rootCause"), "").trim();
            String type = normalizeJudgeStatus(safeString(category.get("type"), "UNKNOWN"));
            boolean confirmed = rootCause.startsWith("\u5df2\u786e\u8ba4\uff1a");
            boolean pending = rootCause.startsWith("\u5f85\u9a8c\u8bc1\uff1a");
            if (isDetailedAiCategory(category, rootCause, type, confirmed, pending)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void guardUnprovenWrongAnswerClaims(
            Map<String, Object> data,
            List<StudentSubmissionAttempt> attempts) {
        Map<Long, Integer> realErrorCountByProblem = new LinkedHashMap<>();
        if (attempts != null) {
            for (StudentSubmissionAttempt attempt : attempts) {
                if (attempt == null || attempt.getProblemId() == null
                        || isAcceptedStatus(attempt.getJudgeStatus())) continue;
                realErrorCountByProblem.merge(attempt.getProblemId(), 1, Integer::sum);
            }
        }
        Object rawAnalyses = data.get("problemAnalyses");
        if (!(rawAnalyses instanceof List<?> analyses)) return;
        LinkedHashSet<Long> guardedProblemIds = new LinkedHashSet<>();
        for (Object item : analyses) {
            if (!(item instanceof Map<?, ?> rawAnalysis)) continue;
            Map<String, Object> analysis = (Map<String, Object>) rawAnalysis;
            Long problemId = parseProblemId(analysis.get("problemId"));
            Object rawCategories = analysis.get("errorCategories");
            if (!(rawCategories instanceof List<?> categories)) continue;
            boolean guarded = false;
            for (Object categoryItem : categories) {
                if (!(categoryItem instanceof Map<?, ?> rawCategory)) continue;
                Map<String, Object> category = (Map<String, Object>) rawCategory;
                String type = normalizeJudgeStatus(safeString(category.get("type"), "UNKNOWN"));
                if (!"WRONG_ANSWER".equals(type)) continue;

                int count = problemId != null
                        ? realErrorCountByProblem.getOrDefault(problemId, 1)
                        : 1;
                category.put("count", count);
                category.put("isSystemic", count >= 2);
                category.put("rootCause", "待验证：当前只有判题状态、得分、题面和最新代码快照，"
                        + "没有失败输入、期望输出及实际输出，不能确认唯一根因。"
                        + "以下代码位置仅作为复现时的候选核查点。");

                List<String> issues = new ArrayList<>();
                issues.add("判题证据：本题出现 " + count
                        + " 次未通过记录；现有数据没有给出具体失败用例。历史记录中的代码字段也不能证明每次提交内容完全相同。");
                LinkedHashSet<String> candidates = new LinkedHashSet<>();
                Object rawIssues = category.get("specificIssues");
                if (rawIssues instanceof List<?> modelIssues) {
                    for (Object modelIssue : modelIssues) {
                        String candidate = sanitizePendingCandidate(safeString(modelIssue, ""));
                        if (candidate.isBlank()) continue;
                        candidates.add(candidate);
                        if (candidates.size() >= 2) break;
                    }
                }
                for (String candidate : candidates) {
                    issues.add("候选核查点（未确认）：" + candidate
                            + "；请结合题面和真实失败用例核对，当前不能判定该表达式就是根因。");
                }
                if (issues.size() == 1) {
                    issues.add("候选核查点（未确认）：当前模型未定位到可靠代码表达式；"
                            + "请逐项核对题面的输入类型、分支边界和输出格式。");
                }
                category.put("specificIssues", issues);
                category.put("suggestions", List.of(
                        "步骤1：逐个运行题面官方样例，记录输入、程序实际输出和按题面手工推导的结果。",
                        "步骤2：围绕上面的候选表达式补充边界输入，定位第一组实际输出与手工结果不一致的用例。",
                        "步骤3：只修改该失败用例对应的表达式，再用官方样例、失败用例和相邻边界值回归验证。"));
                guarded = true;
                if (problemId != null) guardedProblemIds.add(problemId);
            }
            if (guarded) {
                analysis.put("overallAssessment", "本题存在真实未通过记录，但当前数据库没有保存失败测试点的输入、"
                        + "期望输出和实际输出。下面展示的是基于真实题面与最新代码快照的候选核查点，不把推测冒充已确认根因。");
            }
        }
        if (!guardedProblemIds.isEmpty()) {
            guardTopLevelWrongAnswerSummary(data, guardedProblemIds, realErrorCountByProblem);
        }
    }

    @SuppressWarnings("unchecked")
    private void guardTopLevelWrongAnswerSummary(
            Map<String, Object> data,
            LinkedHashSet<Long> guardedProblemIds,
            Map<Long, Integer> realErrorCountByProblem) {
        int totalErrorCount = guardedProblemIds.stream()
                .mapToInt(problemId -> realErrorCountByProblem.getOrDefault(problemId, 1))
                .sum();
        data.put("overallAssessment", "真实判题记录显示 " + guardedProblemIds.size()
                + " 道题共出现 " + totalErrorCount + " 次未通过。"
                + "数据库没有保存失败输入、期望输出和实际输出，因此不能确认唯一根因；"
                + "请按逐题卡片中的候选代码位置完成官方样例和边界用例复现。");

        List<Map<String, Object>> categories = new ArrayList<>();
        Object rawCategories = data.get("errorCategories");
        if (rawCategories instanceof List<?> modelCategories) {
            for (Object item : modelCategories) {
                if (!(item instanceof Map<?, ?> rawCategory)) continue;
                Map<String, Object> category = (Map<String, Object>) rawCategory;
                String type = normalizeJudgeStatus(safeString(category.get("type"), "UNKNOWN"));
                if (!"WRONG_ANSWER".equals(type)) categories.add(category);
            }
        }
        Map<String, Object> guardedCategory = new LinkedHashMap<>();
        guardedCategory.put("type", "WRONG_ANSWER");
        guardedCategory.put("count", totalErrorCount);
        guardedCategory.put("isSystemic", totalErrorCount >= 2);
        guardedCategory.put("rootCause", "待验证：当前缺少失败输入、期望输出和实际输出，"
                + "不能把代码审查候选项认定为真实失败根因。");
        guardedCategory.put("specificIssues", List.of(
                "判题证据：受影响题目和次数已按 problemId 从真实提交记录统计。",
                "证据边界：逐题卡片只展示真实判题记录和当前代码快照中的候选位置。"));
        guardedCategory.put("suggestions", List.of(
                "步骤1：按逐题卡片运行官方样例并记录实际输出。",
                "步骤2：围绕候选表达式补充边界输入，找到第一组真实失败用例。",
                "步骤3：修改对应表达式后，用官方样例、失败用例和相邻边界值回归。"));
        categories.add(guardedCategory);
        data.put("errorCategories", categories);
        data.put("learningSuggestions", List.of(Map.of(
                "priority", totalErrorCount >= 2 ? "HIGH" : "MEDIUM",
                "topic", "失败用例复现与边界验证",
                "reason", "当前只有真实未通过记录，缺少判题器失败测试点明细",
                "suggestedResources", "按逐题定位依据运行官方样例并补充相邻边界输入")));
    }

    private String sanitizePendingCandidate(String value) {
        if (value == null || value.isBlank()) return "";
        String sanitized = value.trim()
                .replace("已确认：", "")
                .replace("已确认:", "")
                .replace("候选核查点（未确认）：", "")
                .replace("候选核查点：", "")
                .trim();
        if (containsUnprovenHistoricalCodeClaim(sanitized)) return "";
        int cutAt = sanitized.length();
        String[] inferenceBoundaries = {
                "，", "。", "；", ";", "导致", "因此", "所以", "从而",
                "违反题面", "应为", "期望输出", "实际应"
        };
        for (String boundary : inferenceBoundaries) {
            int index = sanitized.indexOf(boundary);
            if (index > 0 && index < cutAt) cutAt = index;
        }
        sanitized = sanitized.substring(0, cutAt).trim();
        sanitized = sanitized
                .replace("条件错误", "条件")
                .replace("逻辑错误", "逻辑")
                .replace("边界错误", "边界");
        while (sanitized.endsWith(",") || sanitized.endsWith(":")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        return looksLikeObservableCodeReference(sanitized) ? limitText(sanitized, 80) : "";
    }

    private boolean containsUnprovenHistoricalCodeClaim(String value) {
        String normalized = value.replaceAll("\\s+", "");
        return normalized.contains("相同代码") || normalized.contains("每次提交")
                || normalized.contains("历次提交") || normalized.contains("均得")
                || normalized.matches(".*(?:多次|两次|三次|四次|五次|\\d+次)提交.*");
    }

    private boolean looksLikeObservableCodeReference(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase();
        return lower.contains("代码") || lower.contains("表达式") || lower.contains("条件")
                || lower.contains("变量") || lower.contains("循环") || lower.contains("分支")
                || lower.contains("声明") || lower.matches(".*第\\s*\\d+\\s*行.*")
                || lower.contains("printf") || lower.contains("scanf")
                || lower.contains("cout") || lower.contains("cin")
                || lower.contains("int ") || lower.contains("double ")
                || lower.contains("float ") || lower.contains("return ")
                || lower.contains("==") || lower.contains("!=") || lower.contains("<=")
                || lower.contains(">=") || lower.contains("%")
                || lower.contains("[") || lower.contains("]")
                || lower.contains("(") || lower.contains(")");
    }

    private boolean containsUncertaintyMarker(String text) {
        return text.contains("可能") || text.contains("推测") || text.contains("需验证")
                || text.contains("证据不足") || text.contains("？") || text.contains("?");
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
            category.put("suggestions", ruleSuggestions(type, safeAttempts));
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

    private List<String> ruleSuggestions(
            String type,
            List<StudentSubmissionAttempt> problemAttempts) {
        if (!"COMPILE_ERROR".equals(type)) return ruleSuggestions(type);

        String diagnostic = firstRealDiagnostic(problemAttempts);
        if (!diagnostic.isBlank()) {
            return List.of(
                    "先按真实编译器诊断定位：" + limitText(diagnostic, 90),
                    "检查该诊断对应的声明、括号或类型，再重新编译确认错误是否消失。");
        }
        return List.of(
                "当前真实提交记录没有保存编译器诊断，暂时不能可靠指出行号；请重新提交并保留完整报错信息。",
                "重新提交后对照最新代码逐处核对声明、括号和类型，确认编译器不再报错。");
    }

    private List<String> buildRuleSpecificIssues(
            String type,
            List<StudentSubmissionAttempt> problemAttempts) {
        List<String> issues = new ArrayList<>();
        issues.add("真实判题状态：" + type + "；题目：" + resolveProblemTitle(problemAttempts));
        String diagnostic = firstRealDiagnostic(problemAttempts);
        if (!diagnostic.isBlank()) {
            issues.add("真实判题器诊断：" + limitText(diagnostic, 100));
        } else if ("COMPILE_ERROR".equals(type)) {
            issues.add("当前数据库没有保存编译器诊断，不能凭空指定错误行号；需要重新提交获取诊断。");
        }
        StudentSubmissionAttempt latest = latestAttempt(problemAttempts);
        String latestCode = latest != null ? cleanCode(latest.getCode()) : "";
        if (!latestCode.isBlank()) {
            issues.add("当前题存在最新代码快照；修改时应对照该快照与真实诊断，不把历史提交代码混作当前代码。");
        }
        return issues;
    }

    private String firstRealDiagnostic(List<StudentSubmissionAttempt> problemAttempts) {
        if (problemAttempts == null) return "";
        for (StudentSubmissionAttempt attempt : problemAttempts) {
            String diagnostic = extractRawDiagnostic(attempt != null ? attempt.getRawJson() : null);
            if (!diagnostic.isBlank()) return diagnostic;
        }
        return "";
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

    private List<TeacherSubmissionProblemRow> findProblemRowsForAnalysis(
            String studentNo,
            int experimentId) {
        try {
            List<TeacherSubmissionProblemRow> rows = teacherExperimentQueryDao
                    .findSubmissionProblemRows(studentNo, experimentId);
            return rows != null ? rows : new ArrayList<>();
        } catch (Exception e) {
            logger.warn("Failed to load current problem states for error analysis: student={}, experiment={}, error={}",
                    studentNo, experimentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 缓存命中路径专用：重新加载提交记录，供 normalizeProblemAnalyses 按题回填
     * （全 AC 题目 AI 不输出 problemAnalyses，若不回填前端会显示“没有可量化错误记录”）。
     */
    private List<StudentSubmissionAttempt> loadAttemptsForNormalization(String studentNo, int experimentId) {
        try {
            List<TeacherSubmissionProblemRow> rows = findProblemRowsForAnalysis(studentNo, experimentId);
            List<StudentSubmissionAttempt> primary = teacherExperimentQueryDao
                    .findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);
            List<StudentSubmissionAttempt> attempts = primary == null ? new ArrayList<>() : new ArrayList<>(primary);
            mergeRawAttempts(attempts, findRawAttemptsForAnalysis(studentNo, experimentId));
            appendCurrentProblemStates(attempts, rows);
            return attempts;
        } catch (Exception e) {
            logger.warn("Failed to load attempts for cached analysis normalization: student={}, experiment={}, error={}",
                    studentNo, experimentId, e.getMessage());
            return null;
        }
    }

    private List<StudentSubmissionAttempt> findRawAttemptsForAnalysis(
            String studentNo,
            int experimentId) {
        try {
            List<StudentSubmissionAttempt> attempts = teacherExperimentQueryDao
                    .findSubmissionAttemptsFromRaw(studentNo, experimentId);
            logger.info("analyzeErrorFromDb: {} raw fallback rows for student={}, experiment={}",
                    attempts != null ? attempts.size() : 0, studentNo, experimentId);
            return attempts != null ? attempts : new ArrayList<>();
        } catch (Exception e) {
            logger.warn("Raw submission fallback query failed: student={}, experiment={}, error={}",
                    studentNo, experimentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void mergeRawAttempts(
            List<StudentSubmissionAttempt> target,
            List<StudentSubmissionAttempt> candidates) {
        LinkedHashSet<String> rawEvidence = new LinkedHashSet<>();
        LinkedHashSet<String> fallbackEvidence = new LinkedHashSet<>();
        for (StudentSubmissionAttempt attempt : target) {
            if (attempt == null || attempt.getProblemId() == null) continue;
            String rawKey = rawAttemptEvidenceKey(attempt);
            if (rawKey != null) rawEvidence.add(rawKey);
            fallbackEvidence.add(fallbackAttemptEvidenceKey(attempt));
        }
        for (StudentSubmissionAttempt candidate : candidates) {
            if (candidate == null || candidate.getProblemId() == null) continue;
            String rawKey = rawAttemptEvidenceKey(candidate);
            String fallbackKey = fallbackAttemptEvidenceKey(candidate);
            if ((rawKey != null && rawEvidence.contains(rawKey))
                    || fallbackEvidence.contains(fallbackKey)) continue;

            target.add(candidate);
            if (rawKey != null) rawEvidence.add(rawKey);
            fallbackEvidence.add(fallbackKey);
        }
    }

    private String rawAttemptEvidenceKey(StudentSubmissionAttempt attempt) {
        String rawJson = attempt.getRawJson();
        if (rawJson == null || rawJson.isBlank()) return null;
        return attempt.getProblemId() + "|raw|" + rawJson.trim();
    }

    private String fallbackAttemptEvidenceKey(StudentSubmissionAttempt attempt) {
        return attempt.getProblemId()
                + "|" + normalizeJudgeStatus(attempt.getJudgeStatus())
                + "|" + (attempt.getSubmittedAt() != null ? attempt.getSubmittedAt().getTime() : "")
                + "|" + String.valueOf(attempt.getScore())
                + "|" + String.valueOf(attempt.getRuntimeMs())
                + "|" + String.valueOf(attempt.getMemoryKb());
    }

    private void appendCurrentProblemStates(
            List<StudentSubmissionAttempt> attempts,
            List<TeacherSubmissionProblemRow> problemRows) {
        for (TeacherSubmissionProblemRow row : problemRows) {
            if (!hasQuantifiableProblemState(row)) continue;
            List<StudentSubmissionAttempt> problemAttempts = attempts.stream()
                    .filter(attempt -> attempt != null && row.getProblemId().equals(attempt.getProblemId()))
                    .collect(Collectors.toList());
            StudentSubmissionAttempt latest = latestAttempt(problemAttempts);
            if (matchesCurrentProblemState(latest, row)) continue;

            StudentSubmissionAttempt current = new StudentSubmissionAttempt();
            current.setProblemId(row.getProblemId());
            current.setProblemTitle(row.getProblemTitle());
            current.setJudgeStatus(row.getLatestStatus());
            current.setSubmittedAt(row.getSubmitTime());
            current.setCode(row.getCode());
            attempts.add(current);
        }
    }

    private boolean hasQuantifiableProblemState(TeacherSubmissionProblemRow row) {
        return row != null
                && row.getProblemId() != null
                && row.getLatestStatus() != null
                && !row.getLatestStatus().isBlank();
    }

    private boolean matchesCurrentProblemState(
            StudentSubmissionAttempt attempt,
            TeacherSubmissionProblemRow row) {
        if (attempt == null) return false;
        boolean sameStatus = normalizeJudgeStatus(attempt.getJudgeStatus())
                .equals(normalizeJudgeStatus(row.getLatestStatus()));
        String currentCode = cleanCode(row.getCode());
        boolean sameCode = currentCode.isBlank() || currentCode.equals(cleanCode(attempt.getCode()));
        return sameStatus && sameCode;
    }

    @SuppressWarnings("unchecked")
    private boolean cacheCoversCurrentProblems(
            Map<String, Object> responseBody,
            List<TeacherSubmissionProblemRow> problemRows) {
        if (!hasProblemAnalyses(responseBody)) return false;
        LinkedHashSet<Long> expectedProblemIds = problemRows.stream()
                .filter(this::hasQuantifiableProblemState)
                .map(TeacherSubmissionProblemRow::getProblemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (expectedProblemIds.isEmpty()) return true;

        Map<String, Object> data = extractDataMap(responseBody);
        LinkedHashSet<Long> cachedProblemIds = new LinkedHashSet<>();
        Object rawAnalyses = data != null ? data.get("problemAnalyses") : null;
        if (rawAnalyses instanceof List<?> analyses) {
            for (Object item : analyses) {
                if (item instanceof Map<?, ?> analysis) {
                    Long problemId = parseProblemId(((Map<String, Object>) analysis).get("problemId"));
                    if (problemId != null) cachedProblemIds.add(problemId);
                }
            }
        }
        return cachedProblemIds.containsAll(expectedProblemIds);
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
            category.put("specificIssues", buildRuleSpecificIssues(type, problemAttempts));
            category.put("suggestions", ruleSuggestions(type, problemAttempts));
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
