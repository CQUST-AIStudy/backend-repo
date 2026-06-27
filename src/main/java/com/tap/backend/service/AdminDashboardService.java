package com.tap.backend.service;

import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.SubmissionStatus;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.quota.UserDailyQuotaUsageEntity;
import com.tap.backend.quota.UserDailyQuotaUsageRepository;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.repo.TeachingClassRepository;
import com.tap.backend.repo.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Service
public class AdminDashboardService {

  private final UserRepository userRepository;
  private final TeachingClassRepository classRepository;
  private final UserDailyQuotaUsageRepository usageRepository;
  private final PtaCookieService ptaCookieService;
  private final TeachingClassService teachingClassService;
  private final GradingTaskRepository gradingTaskRepository;
  private final GradingSubmissionRepository gradingSubmissionRepository;
  private final RestTemplate restTemplate;

  @Value("${tap.quota.translation-chars-per-day:200000}")
  private long translationCharsLimit;

  @Value("${tap.quota.ai-requests-per-day:200}")
  private long aiRequestsLimit;

  @Value("${tap.quota.admin-unlimited:true}")
  private boolean adminUnlimited;

  @Value("${tap.ai.provider:openai}")
  private String aiProvider;

  @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}")
  private String openAiBaseUrl;

  @Value("${tap.ai.openai.api-key:}")
  private String openAiApiKey;

  @Value("${tap.ai.openai.model:deepseek-chat}")
  private String openAiModel;

  @Value("${tap.translation.provider:deepl}")
  private String translationProvider;

  @Value("${tap.translation.deepl.base-url:https://api-free.deepl.com}")
  private String deepLBaseUrl;

  @Value("${tap.translation.deepl.api-key:}")
  private String deepLApiKey;

  @Value("${tap.rag.dashscope.api-key:}")
  private String dashScopeApiKey;

  @Value("${tap.rag.dashscope.embedding-model:text-embedding-v3}")
  private String dashScopeModel;

  @Value("${tap.rag.web.tavily-api-key:}")
  private String tavilyApiKey;

  @Value("${pta.spider-url:http://127.0.0.1:8100}")
  private String spiderUrl;

  public AdminDashboardService(
      UserRepository userRepository,
      TeachingClassRepository classRepository,
      UserDailyQuotaUsageRepository usageRepository,
      PtaCookieService ptaCookieService,
      TeachingClassService teachingClassService,
      GradingTaskRepository gradingTaskRepository,
      GradingSubmissionRepository gradingSubmissionRepository,
      @Value("${pta.connect-timeout-ms:5000}") int connectTimeoutMs,
      @Value("${pta.read-timeout-ms:20000}") int readTimeoutMs) {
    this.userRepository = userRepository;
    this.classRepository = classRepository;
    this.usageRepository = usageRepository;
    this.ptaCookieService = ptaCookieService;
    this.teachingClassService = teachingClassService;
    this.gradingTaskRepository = gradingTaskRepository;
    this.gradingSubmissionRepository = gradingSubmissionRepository;

    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
    requestFactory.setReadTimeout(Math.max(1000, readTimeoutMs));
    this.restTemplate = new RestTemplate(requestFactory);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getOverview() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    List<UserDailyQuotaUsageEntity> dailyUsage = usageRepository.findAllByUsageDate(today);
    long aiRequestsUsed = dailyUsage.stream().mapToLong(UserDailyQuotaUsageEntity::getAiRequests).sum();
    long translationCharsUsed = dailyUsage.stream().mapToLong(UserDailyQuotaUsageEntity::getTranslationChars).sum();

    List<TeachingClassEntity> classes = classRepository.findAll();
    Map<Long, UserEntity> teachersById = userRepository.findAllById(
            classes.stream()
                .map(TeachingClassEntity::getTeacherId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
        .stream()
        .collect(Collectors.toMap(UserEntity::getId, u -> u));

    Map<String, Object> spider = fetchSpiderSummary();
    List<Map<String, Object>> classItems = buildClassItems(classes, teachersById);
    List<Map<String, Object>> recentTasks = extractRecentTasks(spider.get("recentTasks"));

    long enabledClasses = classes.stream().filter(c -> Boolean.TRUE.equals(c.getSyncEnabled())).count();
    long staleClasses = classes.stream().filter(this::needsAttention).count();
    long runningClasses = classes.stream()
        .filter(c -> "RUNNING".equalsIgnoreCase(safeText(c.getSyncStatus())))
        .count();

    // --- P0: 补充 experimentCount / studentCount ---
    long studentCount = classes.stream()
        .mapToLong(tc -> teachingClassService.countStudents(tc.getId()))
        .sum();
    List<GradingTaskEntity> allGradingTasks = gradingTaskRepository.findAll();
    long experimentCount = allGradingTasks.stream()
        .map(GradingTaskEntity::getExperimentId)
        .filter(Objects::nonNull)
        .distinct()
        .count();

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("teacherCount", userRepository.countByRole(UserRole.TEACHER));
    stats.put("adminCount", userRepository.countByRole(UserRole.ADMIN));
    stats.put("classCount", classes.size());
    stats.put("studentCount", studentCount);
    stats.put("experimentCount", experimentCount);
    stats.put("syncEnabledClassCount", enabledClasses);
    stats.put("runningClassCount", runningClasses);
    stats.put("attentionClassCount", staleClasses);
    stats.put("aiRequestsUsedToday", aiRequestsUsed);
    stats.put("aiRequestsLimit", aiRequestsLimit);
    stats.put("translationCharsUsedToday", translationCharsUsed);
    stats.put("translationCharsLimit", translationCharsLimit);

    Map<String, Object> overview = new LinkedHashMap<>();
    overview.put("generatedAt", Instant.now());
    overview.put("stats", stats);
    overview.put("quota", Map.of(
        "date", today.toString(),
        "adminUnlimited", adminUnlimited,
        "aiRequestsUsedToday", aiRequestsUsed,
        "aiRequestsLimit", aiRequestsLimit,
        "translationCharsUsedToday", translationCharsUsed,
        "translationCharsLimit", translationCharsLimit,
        "topUsers", buildTopUsers(dailyUsage, teachersById)
    ));
    overview.put("apiServices", buildApiServices(aiRequestsUsed, translationCharsUsed));
    overview.put("spider", spider);
    overview.put("classes", classItems);
    overview.put("recentTasks", recentTasks);

    // --- P1: riskMetrics 预警中心数据 ---
    overview.put("riskMetrics", buildRiskMetrics(classes, teachersById, allGradingTasks));

    return overview;
  }

  // ============================================================
  //  riskMetrics — 预警中心 4 个 Top5 数组
  //  供前端大屏「预警中心」模块消费，替代 mock 数据
  // ============================================================

  private Map<String, Object> buildRiskMetrics(
      List<TeachingClassEntity> classes,
      Map<Long, UserEntity> teachersById,
      List<GradingTaskEntity> allGradingTasks) {

    // 1. 按班级聚合 grading_submission
    //    通过 grading_task.class_id 关联到 teaching_class
    Map<Long, List<GradingTaskEntity>> tasksByClass = allGradingTasks.stream()
        .filter(t -> t.getClassId() != null)
        .collect(Collectors.groupingBy(GradingTaskEntity::getClassId));

    // 2. 按实验聚合 (experimentId 可能为 null)
    Map<Long, List<GradingTaskEntity>> tasksByExperiment = allGradingTasks.stream()
        .filter(t -> t.getExperimentId() != null)
        .collect(Collectors.groupingBy(GradingTaskEntity::getExperimentId));

    // 批量获取所有评分提交（用于低完成率和低分率计算）
    List<GradingSubmissionEntity> allSubmissions = gradingSubmissionRepository.findAll();

    // 按 taskId 聚合提交
    Map<Long, List<GradingSubmissionEntity>> subsByTask = allSubmissions.stream()
        .collect(Collectors.groupingBy(GradingSubmissionEntity::getTaskId));

    Map<String, Object> riskMetrics = new LinkedHashMap<>();
    riskMetrics.put("lowCompletionClasses", buildLowCompletionClasses(classes, teachersById, tasksByClass, subsByTask));
    riskMetrics.put("lowScoreClasses", buildLowScoreClasses(classes, teachersById, tasksByClass, subsByTask));
    riskMetrics.put("ungradedExperiments", buildUngradedExperiments(allGradingTasks, tasksByExperiment, subsByTask));
    riskMetrics.put("syncAnomalies", buildSyncAnomalies(classes, teachersById));
    return riskMetrics;
  }

  /**
   * 低完成率班级 Top5 — 完成率 &lt; 40%，按完成率升序
   * 完成率 = grading_task.completedCount / grading_task.totalCount
   * 如果一个班级有多个 task，取加权平均
   */
  private List<Map<String, Object>> buildLowCompletionClasses(
      List<TeachingClassEntity> classes,
      Map<Long, UserEntity> teachersById,
      Map<Long, List<GradingTaskEntity>> tasksByClass,
      Map<Long, List<GradingSubmissionEntity>> subsByTask) {

    List<Map<String, Object>> result = new ArrayList<>();

    for (TeachingClassEntity tc : classes) {
      List<GradingTaskEntity> classTasks = tasksByClass.getOrDefault(tc.getId(), List.of());
      if (classTasks.isEmpty()) {
        continue;
      }

      // 计算该班级的总完成率
      long totalSubmissions = 0;
      long completedSubmissions = 0;
      for (GradingTaskEntity task : classTasks) {
        List<GradingSubmissionEntity> subs = subsByTask.getOrDefault(task.getId(), List.of());
        totalSubmissions += subs.size();
        completedSubmissions += subs.stream()
            .filter(s -> s.getStatus() == SubmissionStatus.SCORED
                || s.getStatus() == SubmissionStatus.NEED_MORE_EVIDENCE)
            .count();
      }

      if (totalSubmissions == 0) {
        continue;
      }

      int completionRate = BigDecimal.valueOf(completedSubmissions)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(totalSubmissions), 0, RoundingMode.HALF_UP)
          .intValue();

      if (completionRate >= 40) {
        continue;
      }

      long studentCount = teachingClassService.countStudents(tc.getId());
      UserEntity teacher = teachersById.get(tc.getTeacherId());

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", tc.getId());
      item.put("name", tc.getName());
      item.put("completionRate", completionRate);
      item.put("teacherName", teacher == null
          ? ("teacher-" + tc.getTeacherId())
          : firstNonBlank(teacher.getDisplayName(), teacher.getUsername(), "teacher-" + tc.getTeacherId()));
      item.put("studentCount", studentCount);
      result.add(item);
    }

    return result.stream()
        .sorted(Comparator.comparingInt(m -> (int) m.get("completionRate")))
        .limit(5)
        .toList();
  }

  /**
   * 低分率班级 Top5 — 不及格占比 &gt; 30%，按低分率降序
   * 不及格 = totalScore &lt; 60 (满分100假定)
   */
  private List<Map<String, Object>> buildLowScoreClasses(
      List<TeachingClassEntity> classes,
      Map<Long, UserEntity> teachersById,
      Map<Long, List<GradingTaskEntity>> tasksByClass,
      Map<Long, List<GradingSubmissionEntity>> subsByTask) {

    List<Map<String, Object>> result = new ArrayList<>();

    for (TeachingClassEntity tc : classes) {
      List<GradingTaskEntity> classTasks = tasksByClass.getOrDefault(tc.getId(), List.of());
      if (classTasks.isEmpty()) {
        continue;
      }

      // 收集该班级所有已评分的提交
      List<GradingSubmissionEntity> scoredSubs = classTasks.stream()
          .flatMap(task -> subsByTask.getOrDefault(task.getId(), List.of()).stream())
          .filter(s -> s.getStatus() == SubmissionStatus.SCORED && s.getTotalScore() != null)
          .toList();

      if (scoredSubs.size() < 3) {
        // 样本太少不纳入统计
        continue;
      }

      long failCount = scoredSubs.stream()
          .filter(s -> s.getTotalScore().compareTo(BigDecimal.valueOf(60)) < 0)
          .count();

      int lowScoreRate = BigDecimal.valueOf(failCount)
          .multiply(BigDecimal.valueOf(100))
          .divide(BigDecimal.valueOf(scoredSubs.size()), 0, RoundingMode.HALF_UP)
          .intValue();

      if (lowScoreRate <= 30) {
        continue;
      }

      // 计算均分
      BigDecimal avgScore = scoredSubs.stream()
          .map(GradingSubmissionEntity::getTotalScore)
          .reduce(BigDecimal.ZERO, BigDecimal::add)
          .divide(BigDecimal.valueOf(scoredSubs.size()), 1, RoundingMode.HALF_UP);

      long studentCount = teachingClassService.countStudents(tc.getId());
      UserEntity teacher = teachersById.get(tc.getTeacherId());

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", tc.getId());
      item.put("name", tc.getName());
      item.put("lowScoreRate", lowScoreRate);
      item.put("avgScore", avgScore);
      item.put("teacherName", teacher == null
          ? ("teacher-" + tc.getTeacherId())
          : firstNonBlank(teacher.getDisplayName(), teacher.getUsername(), "teacher-" + tc.getTeacherId()));
      item.put("studentCount", studentCount);
      result.add(item);
    }

    return result.stream()
        .sorted(Comparator.comparingInt(m -> -(int) m.get("lowScoreRate")))
        .limit(5)
        .toList();
  }

  /**
   * 未评分实验 Top5 — 按待评分数降序
   * ungradedCount = grading_task.totalCount - grading_task.completedCount
   */
  private List<Map<String, Object>> buildUngradedExperiments(
      List<GradingTaskEntity> allGradingTasks,
      Map<Long, List<GradingTaskEntity>> tasksByExperiment,
      Map<Long, List<GradingSubmissionEntity>> subsByTask) {

    // 按实验维度聚合
    Map<Long, Integer> ungradedByExp = new LinkedHashMap<>();
    Map<Long, Integer> totalByExp = new LinkedHashMap<>();
    Set<Long> teacherIds = new LinkedHashSet<>();

    for (GradingTaskEntity task : allGradingTasks) {
      Long expId = task.getExperimentId();
      if (expId == null) {
        continue;
      }

      // 精确计算：从 submissions 统计 PENDING/PROCESSING 数量
      List<GradingSubmissionEntity> subs = subsByTask.getOrDefault(task.getId(), List.of());
      long ungraded = subs.stream()
          .filter(s -> s.getStatus() == SubmissionStatus.PENDING || s.getStatus() == SubmissionStatus.PROCESSING)
          .count();
      long total = subs.size();

      ungradedByExp.merge(expId, (int) ungraded, Integer::sum);
      totalByExp.merge(expId, (int) total, Integer::sum);

      if (task.getTeacherId() != null) {
        teacherIds.add(task.getTeacherId());
      }
    }

    // 批量获取教师名称
    Map<Long, UserEntity> teachers = userRepository.findAllById(teacherIds).stream()
        .collect(Collectors.toMap(UserEntity::getId, u -> u));

    // 只保留有待评分的实验
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<Long, Integer> entry : ungradedByExp.entrySet()) {
      Long expId = entry.getKey();
      int ungradedCount = entry.getValue();
      if (ungradedCount <= 0) {
        continue;
      }

      int totalCount = totalByExp.getOrDefault(expId, 0);

      // 找到该实验关联的教师
      String teacherName = tasksByExperiment.getOrDefault(expId, List.of()).stream()
          .map(t -> {
            UserEntity teacher = teachers.get(t.getTeacherId());
            return teacher == null ? null
                : firstNonBlank(teacher.getDisplayName(), teacher.getUsername());
          })
          .filter(Objects::nonNull)
          .findFirst()
          .orElse("—");

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", expId);
      item.put("name", "实验 #" + expId); // experiment 名称需从 experiment 表补查，此处用 ID 占位
      item.put("ungradedCount", ungradedCount);
      item.put("totalCount", totalCount);
      item.put("teacherName", teacherName);
      result.add(item);
    }

    return result.stream()
        .sorted(Comparator.comparingInt(m -> -(int) m.get("ungradedCount")))
        .limit(5)
        .toList();
  }

  /**
   * 同步异常 Top5 — FAILED / 48h 未更新，按异常严重程度排序
   */
  private List<Map<String, Object>> buildSyncAnomalies(
      List<TeachingClassEntity> classes,
      Map<Long, UserEntity> teachersById) {

    return classes.stream()
        .filter(this::needsAttention)
        .sorted(Comparator
            .<TeachingClassEntity, Integer>comparing(tc ->
                "FAILED".equalsIgnoreCase(safeText(tc.getSyncStatus())) ? 0 : 1)
            .thenComparing(TeachingClassEntity::getLastSyncAt,
                Comparator.nullsFirst(Comparator.naturalOrder())))
        .limit(5)
        .map(tc -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", tc.getId());
          item.put("className", tc.getName());
          item.put("ptaKeyword", safeText(tc.getPtaKeyword()));
          item.put("ptaGroupId", safeText(tc.getPtaGroupId()));
          item.put("ptaGroupName", safeText(tc.getPtaGroupName()));
          item.put("status", safeText(tc.getSyncStatus()));
          item.put("lastSync", tc.getLastSyncAt());
          item.put("reason", buildAttentionReason(tc));
          return item;
        })
        .toList();
  }

  @Transactional
  public Map<String, Object> triggerClassSync(Long classId, String mode, boolean force) {
    TeachingClassEntity tc = classRepository.findById(classId)
        .orElseThrow(() -> new NoSuchElementException("class not found"));
    String groupName = firstNonBlank(tc.getPtaGroupName(), null);
    String groupId = firstNonBlank(tc.getPtaGroupId(), null);
    if ((groupName == null || groupName.isBlank()) && (groupId == null || groupId.isBlank())) {
      throw new IllegalStateException("PTA user group is not configured for this class");
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("class_id", classId.intValue());
    if (groupId != null && !groupId.isBlank()) {
      body.put("group_id", groupId.trim());
    }
    if (groupName != null && !groupName.isBlank()) {
      body.put("group_name", groupName.trim());
    }
    body.put("keyword", firstNonBlank(groupName, groupId));
    body.put("mode", normalizeMode(mode));
    body.put("force", force);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

    String previousStatus = tc.getSyncStatus();
    tc.setSyncStatus("RUNNING");
    classRepository.save(tc);
    try {
      ResponseEntity<Map> response = restTemplate.postForEntity(spiderUrl + "/crawl", entity, Map.class);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("classId", classId);
      result.put("className", tc.getName());
      result.put("mode", body.get("mode"));
      result.put("force", force);
      Map<?, ?> responseBody = response.getBody();
      boolean blocked = responseBody != null && Boolean.TRUE.equals(responseBody.get("blocked"));
      boolean accepted = responseBody != null && responseBody.get("task_id") != null;
      if (blocked || !accepted) {
        tc.setSyncStatus(previousStatus == null || previousStatus.isBlank() ? "IDLE" : previousStatus);
        classRepository.save(tc);
        result.put("syncStatus", tc.getSyncStatus());
        result.put("blocked", blocked);
        Object message = responseBody == null ? null : responseBody.get("message");
        result.put("message", message == null ? "task not accepted" : String.valueOf(message));
        return result;
      }
      result.put("syncStatus", "RUNNING");
      result.put("taskId", responseBody.get("task_id"));
      Object message = responseBody.get("message");
      result.put("message", message == null ? "task submitted" : String.valueOf(message));
      result.put("blocked", false);
      return result;
    } catch (Exception ex) {
      tc.setSyncStatus("FAILED");
      classRepository.save(tc);
      throw new RuntimeException("spider call failed: " + ex.getMessage(), ex);
    }
  }

  private List<Map<String, Object>> buildApiServices(long aiRequestsUsed, long translationCharsUsed) {
    List<Map<String, Object>> items = new ArrayList<>();
    items.add(buildServiceItem(
        "AI 对话",
        safeText(aiProvider),
        openAiModel,
        openAiBaseUrl,
        openAiApiKey,
        "OPENAI_API_KEY",
        aiRequestsUsed,
        aiRequestsLimit,
        "requests"
    ));
    items.add(buildServiceItem(
        "文档翻译",
        safeText(translationProvider),
        "DeepL API",
        deepLBaseUrl,
        deepLApiKey,
        "DEEPL_API_KEY",
        translationCharsUsed,
        translationCharsLimit,
        "chars"
    ));
    items.add(buildServiceItem(
        "RAG 向量化",
        "dashscope",
        dashScopeModel,
        "",
        dashScopeApiKey,
        "DASHSCOPE_API_KEY",
        -1,
        -1,
        "untracked"
    ));
    items.add(buildServiceItem(
        "Web 检索兜底",
        "tavily",
        "Web fallback",
        "",
        tavilyApiKey,
        "TAVILY_API_KEY",
        -1,
        -1,
        "untracked"
    ));
    return items;
  }

  private Map<String, Object> buildServiceItem(
      String name,
      String provider,
      String model,
      String endpoint,
      String apiKey,
      String envName,
      long used,
      long limit,
      String usageUnit) {
    boolean configured = apiKey != null && !apiKey.isBlank();
    double usageRate = (used >= 0 && limit > 0) ? (double) used / (double) limit : -1d;
    String status = !configured
        ? "MISSING"
        : usageRate >= 0.9d
            ? "CRITICAL"
            : usageRate >= 0.75d
                ? "WARN"
                : "OK";

    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", name);
    item.put("provider", provider);
    item.put("model", safeText(model));
    item.put("endpoint", safeText(endpoint));
    item.put("configured", configured);
    item.put("status", status);
    item.put("maskedKey", maskKey(apiKey));
    item.put("source", System.getenv(envName) != null ? "ENV" : "CONFIG");
    item.put("envName", envName);
    item.put("usedToday", used);
    item.put("limit", limit);
    item.put("usageUnit", usageUnit);
    item.put("usageRate", usageRate);
    item.put("actionHint", buildActionHint(configured, usageRate, usageUnit));
    return item;
  }

  private List<Map<String, Object>> buildTopUsers(
      List<UserDailyQuotaUsageEntity> dailyUsage,
      Map<Long, UserEntity> teachersById) {
    return dailyUsage.stream()
        .sorted(Comparator
            .comparingLong((UserDailyQuotaUsageEntity it) -> it.getAiRequests() * 1000L + it.getTranslationChars())
            .reversed())
        .limit(6)
        .map(item -> {
          UserEntity user = teachersById.get(item.getUserId());
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("userId", item.getUserId());
          row.put("username", user == null ? ("user-" + item.getUserId()) : user.getUsername());
          row.put("displayName", user == null ? "" : safeText(user.getDisplayName()));
          row.put("aiRequests", item.getAiRequests());
          row.put("translationChars", item.getTranslationChars());
          row.put("updatedAt", item.getUpdatedAt());
          return row;
        })
        .toList();
  }

  private List<Map<String, Object>> buildClassItems(
      List<TeachingClassEntity> classes,
      Map<Long, UserEntity> teachersById) {
    return classes.stream()
        .sorted(Comparator.comparing(
            TeachingClassEntity::getUpdatedAt,
            Comparator.nullsLast(Comparator.reverseOrder())))
        .map(tc -> {
          UserEntity teacher = teachersById.get(tc.getTeacherId());
          long studentCount = teachingClassService.countStudents(tc.getId());
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("id", tc.getId());
          item.put("name", tc.getName());
          item.put("teacherName", teacher == null
              ? ("teacher-" + tc.getTeacherId())
              : firstNonBlank(teacher.getDisplayName(), teacher.getUsername(), "teacher-" + tc.getTeacherId()));
          item.put("studentCount", studentCount);
          item.put("ptaKeyword", safeText(tc.getPtaKeyword()));
          item.put("ptaGroupId", safeText(tc.getPtaGroupId()));
          item.put("ptaGroupName", safeText(tc.getPtaGroupName()));
          item.put("syncEnabled", Boolean.TRUE.equals(tc.getSyncEnabled()));
          item.put("syncStatus", safeText(tc.getSyncStatus()));
          item.put("lastSyncAt", tc.getLastSyncAt());
          item.put("updatedAt", tc.getUpdatedAt());
          item.put("attention", needsAttention(tc));
          item.put("attentionReason", buildAttentionReason(tc));
          return item;
        })
        .toList();
  }

  private boolean needsAttention(TeachingClassEntity tc) {
    if (!Boolean.TRUE.equals(tc.getSyncEnabled())) {
      return false;
    }
    String status = safeText(tc.getSyncStatus()).toUpperCase(Locale.ROOT);
    if ("FAILED".equals(status)) {
      return true;
    }
    Instant lastSyncAt = tc.getLastSyncAt();
    return lastSyncAt == null || lastSyncAt.isBefore(Instant.now().minusSeconds(48 * 3600));
  }

  private String buildAttentionReason(TeachingClassEntity tc) {
    if (!Boolean.TRUE.equals(tc.getSyncEnabled())) {
      return "";
    }
    String status = safeText(tc.getSyncStatus()).toUpperCase(Locale.ROOT);
    if ("FAILED".equals(status)) {
      return "最近一次同步失败";
    }
    if (tc.getLastSyncAt() == null) {
      return "尚未完成首次同步";
    }
    if (tc.getLastSyncAt().isBefore(Instant.now().minusSeconds(48 * 3600))) {
      return "超过 48 小时未更新";
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fetchSpiderSummary() {
    Map<String, Object> result = new LinkedHashMap<>();
    boolean healthy = false;
    try {
      ResponseEntity<Map> response = restTemplate.getForEntity(spiderUrl + "/health", Map.class);
      healthy = response.getStatusCode().is2xxSuccessful();
      result.put("healthPayload", response.getBody());
    } catch (Exception ex) {
      result.put("healthError", ex.getMessage());
    }

    result.put("healthy", healthy);
    result.put("baseUrl", spiderUrl);

    Map<String, Object> cookie = ptaCookieService.getStatusSnapshot();
    result.put("cookieStatus", cookie.getOrDefault("status", "UNKNOWN"));
    result.put("cookieError", cookie.getOrDefault("error", ""));
    result.put("cookieLastUpdated", cookie.get("lastUpdated"));

    try {
      ResponseEntity<List> response = restTemplate.getForEntity(spiderUrl + "/tasks", List.class);
      result.put("recentTasks", response.getBody() == null ? List.of() : response.getBody());
    } catch (Exception ex) {
      result.put("recentTasks", List.of());
      result.put("tasksError", ex.getMessage());
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extractRecentTasks(Object rawTasks) {
    if (!(rawTasks instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .limit(8)
        .map(task -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("taskId", task.getOrDefault("task_id", ""));
          item.put("groupName", task.getOrDefault("group_name", task.getOrDefault("group_id", "")));
          item.put("mode", task.getOrDefault("mode", "incremental"));
          item.put("status", task.getOrDefault("status", ""));
          item.put("createdAt", task.getOrDefault("created_at", ""));
          item.put("newSetsCount", task.getOrDefault("new_sets_count", 0));
          item.put("refreshedCount", task.getOrDefault("refreshed_count", 0));
          item.put("submissionsCount", task.getOrDefault("submissions_count", 0));
          item.put("force", task.getOrDefault("force", false));
          item.put("error", task.getOrDefault("error", ""));
          return item;
        })
        .toList();
  }

  private String normalizeMode(String mode) {
    String normalized = safeText(mode).toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "submissions", "refresh", "full", "incremental" -> normalized;
      default -> "incremental";
    };
  }

  private String buildActionHint(boolean configured, double usageRate, String usageUnit) {
    if (!configured) {
      return "补充 Key 后再启用";
    }
    if (usageRate < 0) {
      return "当前服务未接入用量统计";
    }
    if (usageRate >= 0.9d) {
      return "接近当日上限，建议立即充值或切换备用 Key";
    }
    if (usageRate >= 0.75d) {
      return "进入预警区间，建议准备备用 Key";
    }
    if ("chars".equals(usageUnit)) {
      return "翻译额度正常";
    }
    return "当前可继续使用";
  }

  private String maskKey(String key) {
    if (key == null || key.isBlank()) {
      return "";
    }
    String trimmed = key.trim();
    if (trimmed.length() <= 8) {
      return "****";
    }
    return trimmed.substring(0, 3) + "..." + trimmed.substring(trimmed.length() - 4);
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  private String safeText(String value) {
    return value == null ? "" : value;
  }
}
