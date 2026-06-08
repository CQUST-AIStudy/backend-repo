package com.tap.backend.academic.service;

import com.tap.backend.academic.config.SkillTreeConfig;
import com.tap.backend.academic.dao.ProfileDao;
import com.tap.backend.academic.dao.UserDao;
import com.tap.backend.academic.entity.UserEntity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;

@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    @Autowired
    private ProfileDao profileDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private SkillTreeConfig skillTreeConfig;

    @Value("${tap.ai.openai.api-key:}")
    private String deepseekApiKey;

    @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    @Value("${tap.ai.openai.model:deepseek-chat}")
    private String deepseekModel;

    private static final Gson gson = new Gson();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    // ========== 学生画像 ==========

    @Cacheable(value = "studentProfile", key = "#studentId")
    public Map<String, Object> getStudentProfile(String studentId) {
        Map<String, Object> studentInfo = profileDao.getStudentInfo(studentId);
        List<Map<String, Object>> expStats = profileDao.getStudentExperimentStats(studentId);

        if (expStats == null || expStats.isEmpty()) {
            return Map.of("error", "该学生无提交记录", "studentId", studentId);
        }

        // 按实验ID索引
        Map<Integer, Map<String, Object>> statsByExp = new LinkedHashMap<>();
        for (Map<String, Object> row : expStats) {
            int expId = ((Number) row.get("experiment_id")).intValue();
            statsByExp.put(expId, row);
        }

        // 1. 计算每个实验的 mastery
        Map<Integer, Double> expMastery = new LinkedHashMap<>();
        Map<Integer, Double> expConfidence = new LinkedHashMap<>();
        for (var entry : statsByExp.entrySet()) {
            int expId = entry.getKey();
            Map<String, Object> s = entry.getValue();
            double mastery = computeMastery(s);
            double confidence = computeConfidence(s);
            expMastery.put(expId, mastery);
            expConfidence.put(expId, confidence);
        }

        // 2. 计算一级维度分数（雷达图）
        Map<String, Object> radarData = computeRadar(expMastery, expConfidence, statsByExp);

        // 3. 技能树详情
        List<Map<String, Object>> skillTree = buildSkillTree(expMastery, expConfidence, statsByExp);

        // 4. Top3 薄弱点
        List<Map<String, Object>> weaknesses = findWeaknesses(expMastery, expConfidence, statsByExp, studentId);

        // 5. 趋势（前半学期 vs 后半学期）
        Map<String, Object> trend = computeTrend(expMastery, statsByExp);

        // 6. 学习习惯标签
        List<Map<String, Object>> patterns = detectPatterns(statsByExp, expMastery);

        // 7. 总体统计
        Map<String, Object> overview = computeOverview(statsByExp);

        // 8. LLM 文案（DeepSeek，失败则 fallback 到模板）
        String feedback = generateFeedback(studentInfo, radarData, weaknesses, patterns, overview, trend);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("studentName", studentInfo != null ? studentInfo.get("name") : "未知");
        result.put("className", studentInfo != null ? studentInfo.get("class_name") : "未知");
        result.put("overview", overview);
        result.put("radar", radarData);
        result.put("skillTree", skillTree);
        result.put("weaknesses", weaknesses);
        result.put("trend", trend);
        result.put("patterns", patterns);
        result.put("feedback", feedback);
        return result;
    }

    private double computeMastery(Map<String, Object> stats) {
        long total = ((Number) stats.get("total_submissions")).longValue();
        long ac = ((Number) stats.get("ac_count")).longValue();
        long compileErr = ((Number) stats.get("compile_error_count")).longValue();
        long questions = ((Number) stats.get("question_count")).longValue();

        if (total == 0) return 0;

        double correctRate = (double) ac / total;
        double compileErrRate = (double) compileErr / total;
        // 效率：提交次数越少越好（每题平均提交次数）
        double avgAttemptsPerQ = questions > 0 ? (double) total / questions : total;
        double efficiencyScore = Math.max(0, 1.0 - (avgAttemptsPerQ - 1) / 20.0);

        double mastery = 0.6 * correctRate + 0.2 * (1.0 - compileErrRate) + 0.2 * efficiencyScore;
        return Math.round(mastery * 1000.0) / 10.0; // 0-100
    }

    private double computeConfidence(Map<String, Object> stats) {
        long total = ((Number) stats.get("total_submissions")).longValue();
        return Math.min(1.0, total / 10.0);
    }

    private Map<String, Object> computeRadar(
            Map<Integer, Double> expMastery,
            Map<Integer, Double> expConfidence,
            Map<Integer, Map<String, Object>> statsByExp) {
        List<String> dimensions = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<Double> confidences = new ArrayList<>();

        for (var entry : skillTreeConfig.getDimensions().entrySet()) {
            String dim = entry.getKey();
            double sumScore = 0, sumConf = 0;
            int count = 0;
            for (var masteryEntry : expMastery.entrySet()) {
                int eid = masteryEntry.getKey();
                if (dim.equals(resolveDimension(eid, statsByExp.get(eid)))) {
                    sumScore += masteryEntry.getValue();
                    sumConf += expConfidence.getOrDefault(eid, 0.0);
                    count++;
                }
            }
            dimensions.add(dim);
            scores.add(count > 0 ? Math.round(sumScore / count * 10.0) / 10.0 : 0);
            confidences.add(count > 0 ? Math.round(sumConf / count * 100.0) / 100.0 : 0);
        }

        Map<String, Object> radar = new LinkedHashMap<>();
        radar.put("dimensions", dimensions);
        radar.put("scores", scores);
        radar.put("confidences", confidences);
        return radar;
    }

    private List<Map<String, Object>> buildSkillTree(
            Map<Integer, Double> expMastery,
            Map<Integer, Double> expConfidence,
            Map<Integer, Map<String, Object>> statsByExp) {

        List<Map<String, Object>> tree = new ArrayList<>();
        for (var entry : skillTreeConfig.getDimensions().entrySet()) {
            String dim = entry.getKey();

            List<Map<String, Object>> children = new ArrayList<>();
            double sumScore = 0;
            int count = 0;
            for (int eid : statsByExp.keySet()) {
                if (!dim.equals(resolveDimension(eid, statsByExp.get(eid)))) {
                    continue;
                }
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("experimentId", eid);
                child.put("name", getExperimentDisplayName(eid, statsByExp.get(eid)));
                double m = expMastery.getOrDefault(eid, 0.0);
                double c = expConfidence.getOrDefault(eid, 0.0);
                child.put("mastery", m);
                child.put("confidence", c);
                child.put("level", m >= 70 ? "good" : m >= 40 ? "medium" : "weak");
                // 附加统计
                if (statsByExp.containsKey(eid)) {
                    Map<String, Object> s = statsByExp.get(eid);
                    child.put("totalSubmissions", s.get("total_submissions"));
                    child.put("acCount", s.get("ac_count"));
                    child.put("questionCount", s.get("question_count"));
                }
                children.add(child);
                if (expMastery.containsKey(eid)) {
                    sumScore += m;
                    count++;
                }
            }

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("dimension", dim);
            node.put("description", skillTreeConfig.getDescriptions().get(dim));
            node.put("avgMastery", count > 0 ? Math.round(sumScore / count * 10.0) / 10.0 : 0);
            node.put("level", (count > 0 ? sumScore / count : 0) >= 70 ? "good" :
                              (count > 0 ? sumScore / count : 0) >= 40 ? "medium" : "weak");
            node.put("children", children);
            tree.add(node);
        }
        return tree;
    }

    private List<Map<String, Object>> findWeaknesses(
            Map<Integer, Double> expMastery,
            Map<Integer, Double> expConfidence,
            Map<Integer, Map<String, Object>> statsByExp,
            String studentId) {

        // 按 mastery 升序排列，取 confidence >= 0.5 的前3个
        List<Map.Entry<Integer, Double>> sorted = expMastery.entrySet().stream()
                .filter(e -> expConfidence.getOrDefault(e.getKey(), 0.0) >= 0.3)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(3)
                .collect(Collectors.toList());

        List<Map<String, Object>> weaknesses = new ArrayList<>();
        for (var entry : sorted) {
            int expId = entry.getKey();
            double mastery = entry.getValue();
            Map<String, Object> stats = statsByExp.get(expId);

            // 找到所属维度
            String dimension = resolveDimension(expId, stats);

            // 获取薄弱题目
            List<Map<String, Object>> weakQs = profileDao.getStudentWeakQuestions(studentId, expId);

            Map<String, Object> w = new LinkedHashMap<>();
            w.put("experimentId", expId);
            w.put("experimentName", getExperimentDisplayName(expId, stats));
            w.put("dimension", dimension);
            w.put("mastery", mastery);
            w.put("confidence", expConfidence.getOrDefault(expId, 0.0));
            w.put("evidence", Map.of(
                    "totalSubmissions", stats.get("total_submissions"),
                    "acCount", stats.get("ac_count"),
                    "compileErrors", stats.get("compile_error_count"),
                    "wrongAnswers", stats.get("wrong_answer_count"),
                    "questionCount", stats.get("question_count")
            ));
            w.put("weakQuestions", weakQs != null ? weakQs.stream().limit(3).collect(Collectors.toList()) : List.of());
            weaknesses.add(w);
        }
        return weaknesses;
    }

    private Map<String, Object> computeTrend(
            Map<Integer, Double> expMastery,
            Map<Integer, Map<String, Object>> statsByExp) {
        // 前半学期: exp 1-9, 后半学期: exp 10-19
        double firstHalf = 0, secondHalf = 0;
        int c1 = 0, c2 = 0;
        for (var entry : expMastery.entrySet()) {
            int courseOrder = resolveCourseOrder(entry.getKey(), statsByExp.get(entry.getKey()));
            if (courseOrder <= 9) { firstHalf += entry.getValue(); c1++; }
            else { secondHalf += entry.getValue(); c2++; }
        }
        double avg1 = c1 > 0 ? firstHalf / c1 : 0;
        double avg2 = c2 > 0 ? secondHalf / c2 : 0;
        double diff = avg2 - avg1;

        String direction = diff > 5 ? "up" : diff < -5 ? "down" : "flat";

        // 每个实验的 mastery 序列（用于折线图）
        List<Map<String, Object>> series = new ArrayList<>();
        for (var entry : expMastery.entrySet()) {
            series.add(Map.of(
                    "experimentId", entry.getKey(),
                    "name", getExperimentDisplayName(entry.getKey(), statsByExp.get(entry.getKey())),
                    "mastery", entry.getValue()
            ));
        }

        return Map.of(
                "direction", direction,
                "firstHalfAvg", Math.round(avg1 * 10.0) / 10.0,
                "secondHalfAvg", Math.round(avg2 * 10.0) / 10.0,
                "change", Math.round(diff * 10.0) / 10.0,
                "series", series
        );
    }

    private String getExperimentDisplayName(int experimentId, Map<String, Object> stats) {
        Object name = stats != null ? stats.get("experiment_name") : null;
        if (name != null && !String.valueOf(name).isBlank()) {
            return String.valueOf(name);
        }
        Integer configuredId = resolveConfiguredExperimentId(experimentId, stats);
        return skillTreeConfig.getExperimentName(configuredId != null ? configuredId : experimentId);
    }

    private String resolveDimension(int experimentId, Map<String, Object> stats) {
        Integer configuredId = resolveConfiguredExperimentId(experimentId, stats);
        if (configuredId != null) {
            String configuredDimension = skillTreeConfig.getDimensionForExperiment(configuredId);
            if (!"鏈煡".equals(configuredDimension)) {
                return configuredDimension;
            }
        }

        String name = String.valueOf(stats != null ? stats.getOrDefault("experiment_name", "") : "");
        if (name.contains("Hash") || name.contains("哈希")) return "鍝堝笇";
        if (name.contains("DFS") || name.contains("BFS") || name.contains("Dijkstra") || name.contains("Prim") || name.contains("图")) return "鍥?";
        if (name.contains("Huffman") || name.contains("二叉") || name.contains("BST") || name.contains("树")) return "鏍?";
        if (name.contains("栈") || name.contains("队列")) return "鏍堜笌闃熷垪";
        if (name.contains("链表") || name.contains("顺序表") || name.contains("线性表")) return "绾挎€ц〃";
        if (name.contains("期中") || name.contains("复习") || name.contains("例题")) return "缁煎悎";
        return "鏈煡";
    }

    private int resolveCourseOrder(int experimentId, Map<String, Object> stats) {
        Integer configuredId = resolveConfiguredExperimentId(experimentId, stats);
        return configuredId != null ? configuredId : experimentId;
    }

    private Integer resolveConfiguredExperimentId(int experimentId, Map<String, Object> stats) {
        if (skillTreeConfig.getExperimentNames().containsKey(experimentId)) {
            return experimentId;
        }

        String name = String.valueOf(stats != null ? stats.getOrDefault("experiment_name", "") : "");
        if (name.contains("期中")) return 18;
        if (name.contains("例题")) return 19;

        Matcher matcher = Pattern.compile("第\\s*(\\d+)\\s*次\\s*(作业|实验)").matcher(name);
        if (!matcher.find()) {
            return null;
        }

        int nth = Integer.parseInt(matcher.group(1));
        String type = matcher.group(2);
        if ("作业".equals(type)) {
            return switch (nth) {
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 5;
                case 4 -> 7;
                case 6 -> 10;
                default -> null;
            };
        }
        return switch (nth) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 6;
            case 4 -> 8;
            case 5 -> 9;
            case 6 -> 11;
            case 7 -> 12;
            case 8 -> 13;
            case 9 -> 14;
            case 10 -> 15;
            case 11 -> 16;
            case 12 -> 17;
            default -> null;
        };
    }

    private List<Map<String, Object>> detectPatterns(
            Map<Integer, Map<String, Object>> statsByExp,
            Map<Integer, Double> expMastery) {

        List<Map<String, Object>> patterns = new ArrayList<>();

        // 统计总体数据
        long totalSubmissions = 0, totalAc = 0, totalCompileErr = 0;
        List<Double> masteryValues = new ArrayList<>(expMastery.values());

        for (var s : statsByExp.values()) {
            totalSubmissions += ((Number) s.get("total_submissions")).longValue();
            totalAc += ((Number) s.get("ac_count")).longValue();
            totalCompileErr += ((Number) s.get("compile_error_count")).longValue();
        }

        // 高重做型：平均每题提交次数 > 8
        long totalQuestions = statsByExp.values().stream()
                .mapToLong(s -> ((Number) s.get("question_count")).longValue()).sum();
        double avgAttemptsPerQ = totalQuestions > 0 ? (double) totalSubmissions / totalQuestions : 0;
        if (avgAttemptsPerQ > 8) {
            patterns.add(Map.of(
                    "tag", "高重做型",
                    "description", "平均每题提交" + String.format("%.1f", avgAttemptsPerQ) + "次，建议先理清思路再编码",
                    "evidence", "总提交" + totalSubmissions + "次，覆盖" + totalQuestions + "题"
            ));
        }

        // 编译错误多：编译错误占比 > 30%
        double compileErrRate = totalSubmissions > 0 ? (double) totalCompileErr / totalSubmissions : 0;
        if (compileErrRate > 0.3) {
            patterns.add(Map.of(
                    "tag", "编码基础薄弱",
                    "description", "编译错误占比" + String.format("%.0f%%", compileErrRate * 100) + "，建议加强C语言语法练习",
                    "evidence", "编译错误" + totalCompileErr + "/" + totalSubmissions + "次"
            ));
        }

        // 高波动型：mastery 标准差 > 15
        if (masteryValues.size() >= 3) {
            double mean = masteryValues.stream().mapToDouble(d -> d).average().orElse(0);
            double variance = masteryValues.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
            double stddev = Math.sqrt(variance);
            if (stddev > 15) {
                patterns.add(Map.of(
                        "tag", "高波动型",
                        "description", "各实验表现差异大(标准差" + String.format("%.1f", stddev) + ")，部分知识点掌握不均",
                        "evidence", "mastery范围: " + String.format("%.0f", Collections.min(masteryValues))
                                + "~" + String.format("%.0f", Collections.max(masteryValues))
                ));
            }
        }

        // 稳定进步型：后半学期比前半学期高10+
        double first = 0, second = 0;
        int c1 = 0, c2 = 0;
        for (var e : expMastery.entrySet()) {
            if (e.getKey() <= 9) { first += e.getValue(); c1++; }
            else { second += e.getValue(); c2++; }
        }
        if (c1 > 0 && c2 > 0 && (second / c2 - first / c1) > 10) {
            patterns.add(Map.of(
                    "tag", "稳定进步",
                    "description", "后半学期表现明显提升，学习态度积极",
                    "evidence", "前半学期均分" + String.format("%.1f", first / c1)
                            + " → 后半学期" + String.format("%.1f", second / c2)
            ));
        }

        // 如果没有检测到任何模式
        if (patterns.isEmpty()) {
            patterns.add(Map.of(
                    "tag", "表现均衡",
                    "description", "各方面表现较为均衡，继续保持",
                    "evidence", "总体AC率" + String.format("%.0f%%", totalSubmissions > 0 ? (double) totalAc / totalSubmissions * 100 : 0)
            ));
        }

        return patterns;
    }

    private Map<String, Object> computeOverview(Map<Integer, Map<String, Object>> statsByExp) {
        long totalSub = 0, totalAc = 0;
        int expCount = statsByExp.size();
        for (var s : statsByExp.values()) {
            totalSub += ((Number) s.get("total_submissions")).longValue();
            totalAc += ((Number) s.get("ac_count")).longValue();
        }
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalSubmissions", totalSub);
        overview.put("totalAc", totalAc);
        overview.put("overallAcRate", totalSub > 0 ? Math.round((double) totalAc / totalSub * 1000.0) / 10.0 : 0);
        overview.put("experimentsCovered", expCount);
        overview.put("totalExperiments", skillTreeConfig.getExperimentNames().size());
        return overview;
    }

    /**
     * 构建画像摘要JSON（供LLM和缓存使用）
     */
    private String buildProfileJson(Map<String, Object> studentInfo,
                                     Map<String, Object> radar,
                                     List<Map<String, Object>> weaknesses,
                                     List<Map<String, Object>> patterns,
                                     Map<String, Object> overview,
                                     Map<String, Object> trend) {
        String name = studentInfo != null ? String.valueOf(studentInfo.get("name")) : "同学";
        Map<String, Object> profileSummary = new LinkedHashMap<>();
        profileSummary.put("studentName", name);
        profileSummary.put("overallAcRate", overview.get("overallAcRate"));
        profileSummary.put("totalSubmissions", overview.get("totalSubmissions"));
        profileSummary.put("totalAc", overview.get("totalAc"));
        profileSummary.put("experimentsCovered", overview.get("experimentsCovered"));
        profileSummary.put("radarScores", radar);
        profileSummary.put("trend", Map.of(
                "direction", trend.get("direction"),
                "firstHalfAvg", trend.get("firstHalfAvg"),
                "secondHalfAvg", trend.get("secondHalfAvg"),
                "change", trend.get("change")
        ));
        List<Map<String, Object>> weakSummary = new ArrayList<>();
        for (var w : weaknesses) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("name", w.get("experimentName"));
            ws.put("dimension", w.get("dimension"));
            ws.put("mastery", w.get("mastery"));
            if (w.get("evidence") != null) ws.put("evidence", w.get("evidence"));
            weakSummary.add(ws);
        }
        profileSummary.put("weaknesses", weakSummary);
        profileSummary.put("patterns", patterns);
        return gson.toJson(profileSummary);
    }

    /**
     * 生成反馈：先查DB缓存 → 有则返回 → 无则调DeepSeek → 存DB → 返回
     */
    private String generateFeedback(Map<String, Object> studentInfo,
                                     Map<String, Object> radar,
                                     List<Map<String, Object>> weaknesses,
                                     List<Map<String, Object>> patterns,
                                     Map<String, Object> overview,
                                     Map<String, Object> trend) {
        String studentId = studentInfo != null ? String.valueOf(studentInfo.get("student_id")) : null;
        String name = studentInfo != null ? String.valueOf(studentInfo.get("name")) : "同学";
        String profileJson = buildProfileJson(studentInfo, radar, weaknesses, patterns, overview, trend);

        // 1. 先查DB缓存
        if (studentId != null) {
            try {
                Map<String, Object> cached = profileDao.getAiFeedback(studentId);
                if (cached != null && cached.get("feedback") != null) {
                    String cachedFeedback = String.valueOf(cached.get("feedback"));
                    String cachedProfileJson = cached.get("profile_json") != null
                            ? String.valueOf(cached.get("profile_json"))
                            : "";
                    if (!cachedFeedback.isBlank() && profileJson.equals(cachedProfileJson)) {
                        return cachedFeedback;
                    }
                }
            } catch (Exception e) {
                log.warn("查询缓存失败: {}", e.getMessage());
            }
        }

        // 3. 调用DeepSeek
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) {
            try {
                String llmFeedback = callDeepSeek(profileJson, name);
                if (llmFeedback != null && !llmFeedback.isBlank()) {
                    // 4. 存入DB缓存
                    if (studentId != null) {
                        try {
                            profileDao.saveAiFeedback(studentId, llmFeedback, profileJson);
                        } catch (Exception e) {
                            log.warn("保存缓存失败: {}", e.getMessage());
                        }
                    }
                    return llmFeedback;
                }
            } catch (Exception e) {
                log.warn("DeepSeek调用失败，使用模板: {}", e.getMessage());
            }
        }

        // 5. Fallback: 模板拼接
        return buildTemplateFeedback(name, radar, weaknesses, patterns);
    }

    /**
     * 强制刷新：重新调用DeepSeek分析，更新DB缓存，返回新反馈
     */
    @CacheEvict(value = "studentProfile", key = "#studentId")
    public Map<String, Object> refreshFeedback(String studentId) {
        // 重新计算完整画像
        Map<String, Object> studentInfo = profileDao.getStudentInfo(studentId);
        List<Map<String, Object>> expStats = profileDao.getStudentExperimentStats(studentId);

        if (expStats == null || expStats.isEmpty()) {
            return Map.of("error", "该学生无提交记录", "studentId", studentId);
        }

        Map<Integer, Map<String, Object>> statsByExp = new LinkedHashMap<>();
        for (Map<String, Object> row : expStats) {
            int expId = ((Number) row.get("experiment_id")).intValue();
            statsByExp.put(expId, row);
        }

        Map<Integer, Double> expMastery = new LinkedHashMap<>();
        Map<Integer, Double> expConfidence = new LinkedHashMap<>();
        for (var entry : statsByExp.entrySet()) {
            expMastery.put(entry.getKey(), computeMastery(entry.getValue()));
            expConfidence.put(entry.getKey(), computeConfidence(entry.getValue()));
        }

        Map<String, Object> radar = computeRadar(expMastery, expConfidence, statsByExp);
        List<Map<String, Object>> weaknesses = findWeaknesses(expMastery, expConfidence, statsByExp, studentId);
        Map<String, Object> trend = computeTrend(expMastery, statsByExp);
        List<Map<String, Object>> patterns = detectPatterns(statsByExp, expMastery);
        Map<String, Object> overview = computeOverview(statsByExp);

        String name = studentInfo != null ? String.valueOf(studentInfo.get("name")) : "同学";
        String profileJson = buildProfileJson(studentInfo, radar, weaknesses, patterns, overview, trend);

        // 强制调用DeepSeek（不查缓存）
        String feedback = null;
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) {
            try {
                feedback = callDeepSeek(profileJson, name);
            } catch (Exception e) {
                log.warn("刷新时DeepSeek调用失败: {}", e.getMessage());
            }
        }

        if (feedback == null || feedback.isBlank()) {
            feedback = buildTemplateFeedback(name, radar, weaknesses, patterns);
        }

        // 保存到DB
        try {
            profileDao.saveAiFeedback(studentId, feedback, profileJson);
        } catch (Exception e) {
            log.warn("刷新保存缓存失败: {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId", studentId);
        result.put("feedback", feedback);
        result.put("refreshedAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        return result;
    }

    /**
     * 调用 DeepSeek API 生成学习反馈（精心设计的提示词）
     */
    private String callDeepSeek(String profileJson, String studentName) throws Exception {
        String systemPrompt = "你是一位经验丰富的高校数据结构课程教学助手，负责根据学生在PTA编程平台上的提交数据，生成个性化的学习分析报告。\n\n"
                + "## 数据背景\n"
                + "- 课程：数据结构（C语言实现）\n"
                + "- 平台：PTA（Programming Teaching Assistant）在线编程平台\n"
                + "- 数据来源：学生在19个实验/作业中的所有提交记录，包括AC（通过）、编译错误、答案错误、超时等状态\n"
                + "- 能力维度：线性表、栈与队列、树、图、哈希、综合，每个维度包含若干实验\n"
                + "- mastery分数：0-100，由AC率(60%)、编译正确率(20%)、提交效率(20%)加权计算\n\n"
                + "## 输出格式要求（严格遵守）\n"
                + "请按以下结构输出，使用中文：\n\n"
                + "【总评】（2-3句话，概括该学生的整体学习情况，必须引用具体的AC率、提交次数等数据）\n\n"
                + "【薄弱分析】（针对数据中mastery最低的2-3个实验/维度，分析可能的原因，必须引用具体实验名称和分数）\n\n"
                + "【学习建议】\n"
                + "1. （第一条建议，必须针对具体知识点，如'链表指针操作'、'递归遍历'等，给出可执行的练习方法）\n"
                + "2. （第二条建议，针对学习习惯或策略，如编译错误多则建议先手写伪代码）\n"
                + "3. （第三条建议，针对提升方向，结合趋势数据给出鼓励或警示）\n\n"
                + "## 约束\n"
                + "- 只基于提供的JSON数据进行分析，不要编造任何数据中不存在的信息\n"
                + "- 引用数据时使用原始数值，不要四舍五入或模糊化\n"
                + "- 语气友好、专业、有建设性，像一位关心学生的老师\n"
                + "- 总字数控制在300-500字之间";

        String userPrompt = "以下是" + studentName + "同学在数据结构课程PTA平台上的能力画像数据（JSON格式），请根据上述要求生成学习分析报告：\n\n" + profileJson;

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", deepseekModel);
        reqBody.addProperty("stream", false);
        reqBody.addProperty("max_tokens", 800);
        reqBody.addProperty("temperature", 0.7);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);
        reqBody.add("messages", messages);

        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                reqBody.toString(),
                okhttp3.MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(deepseekBaseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + deepseekApiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("DeepSeek API错误: {} {}", response.code(),
                        response.body() != null ? response.body().string() : "");
                return null;
            }
            String respStr = response.body() != null ? response.body().string() : "";
            JsonObject respJson = JsonParser.parseString(respStr).getAsJsonObject();
            JsonArray choices = respJson.getAsJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message != null && message.has("content")) {
                    return message.get("content").getAsString().trim();
                }
            }
        }
        return null;
    }

    /**
     * Fallback: 模板拼接反馈
     */
    private String buildTemplateFeedback(String name,
                                          Map<String, Object> radar,
                                          List<Map<String, Object>> weaknesses,
                                          List<Map<String, Object>> patterns) {
        List<Double> scores = (List<Double>) radar.get("scores");
        List<String> dims = (List<String>) radar.get("dimensions");

        // 找最强和最弱维度
        int maxIdx = 0, minIdx = 0;
        for (int i = 1; i < scores.size(); i++) {
            if (scores.get(i) > scores.get(maxIdx)) maxIdx = i;
            if (scores.get(i) < scores.get(minIdx)) minIdx = i;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(name).append("同学，");
        sb.append("你在「").append(dims.get(maxIdx)).append("」方面表现最好(").append(String.format("%.0f", scores.get(maxIdx))).append("分)，");
        sb.append("「").append(dims.get(minIdx)).append("」需要加强(").append(String.format("%.0f", scores.get(minIdx))).append("分)。");

        if (!weaknesses.isEmpty()) {
            sb.append("建议重点复习：");
            for (int i = 0; i < weaknesses.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(weaknesses.get(i).get("experimentName"));
            }
            sb.append("。");
        }

        if (!patterns.isEmpty()) {
            sb.append("学习特征：").append(patterns.get(0).get("tag")).append("。");
        }

        return sb.toString();
    }

    // ========== 当前用户个人信息（通用：admin / teacher / student） ==========

    public Map<String, Object> getCurrentUserProfile(UserEntity user) {
        String role = normalizeRole(user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("name", user.getUsername());
        result.put("username", user.getUsername());
        result.put("role", role);
        result.put("email", emptyIfNull(user.getEmail()));
        result.put("phone", emptyIfNull(user.getPhone()));
        result.put("department", emptyIfNull(user.getDepartment()));
        result.put("avatar", "");
        if (user.getUsernum() != null && !user.getUsernum().isBlank()) {
            result.put("usernum", user.getUsernum());
        }
        if (user.getClassname() != null && !user.getClassname().isBlank()) {
            result.put("class", user.getClassname());
        }
        return result;
    }

    public Map<String, Object> updateCurrentUserProfile(UserEntity sessionUser, Map<String, String> data) {
        // 从数据库重新加载完整用户记录，避免 cookie 解析的 UserEntity 缺失 password/email 等字段
        UserEntity user = userDao.findById(sessionUser.getId());
        if (user == null) {
            throw new RuntimeException("user not found");
        }

        String name = data.get("name");
        String email = data.get("email");
        String phone = data.get("phone");
        String department = data.get("department");

        if (name != null && !name.isBlank()) {
            user.setUsername(name.trim());
        }
        if (email != null) {
            user.setEmail(email.trim());
        }
        if (phone != null) {
            user.setPhone(phone.trim());
        }
        if (department != null) {
            user.setDepartment(department.trim());
        }

        userDao.updateUser(user);

        return getCurrentUserProfile(user);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "student";
        }
        return role.trim().toLowerCase();
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    // ========== 班级画像 ==========

    @Cacheable(value = "classProfile", key = "#className == null ? 'ALL' : #className")
    public Map<String, Object> getClassProfile(String className) {
        List<Map<String, Object>> allStats = profileDao.getClassExperimentStats(className);
        List<Map<String, Object>> students = profileDao.getAllStudents(className);
        if (allStats == null) {
            allStats = new ArrayList<>();
        }

        // 按学生分组
        Map<String, List<Map<String, Object>>> byStudent = new LinkedHashMap<>();
        Map<String, String> studentNames = new LinkedHashMap<>();
        for (Map<String, Object> student : students) {
            String sid = String.valueOf(student.get("student_id"));
            String sname = student.get("name") != null ? String.valueOf(student.get("name")) : sid;
            studentNames.put(sid, sname);
            byStudent.putIfAbsent(sid, new ArrayList<>());
        }
        for (Map<String, Object> row : allStats) {
            String sid = String.valueOf(row.get("student_id"));
            byStudent.computeIfAbsent(sid, k -> new ArrayList<>()).add(row);
            if (!studentNames.containsKey(sid) || studentNames.get(sid) == null || studentNames.get(sid).isBlank()) {
                String sname = row.get("student_name") != null ? String.valueOf(row.get("student_name")) : sid;
                studentNames.put(sid, sname);
            }
        }

        // 计算每个学生每个维度的分数
        Map<String, Map<String, Double>> studentDimScores = new LinkedHashMap<>();
        Map<String, Double> studentOverallScores = new LinkedHashMap<>();

        for (var entry : byStudent.entrySet()) {
            String sid = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();

            // 找学生名
            if (!studentNames.containsKey(sid) || studentNames.get(sid) == null || studentNames.get(sid).isBlank()) {
                String sname = rows.isEmpty() || rows.get(0).get("student_name") == null
                        ? sid
                        : String.valueOf(rows.get(0).get("student_name"));
                studentNames.put(sid, sname);
            }

            // 按实验ID索引
            Map<Integer, Map<String, Object>> expMap = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                int eid = ((Number) r.get("experiment_id")).intValue();
                expMap.put(eid, r);
            }

            // 计算每个实验的mastery
            Map<Integer, Double> expMastery = new LinkedHashMap<>();
            for (var e : expMap.entrySet()) {
                long total = ((Number) e.getValue().get("total_submissions")).longValue();
                long ac = ((Number) e.getValue().get("ac_count")).longValue();
                long questions = ((Number) e.getValue().get("question_count")).longValue();
                if (total == 0) { expMastery.put(e.getKey(), 0.0); continue; }
                double correctRate = (double) ac / total;
                double avgAtt = questions > 0 ? (double) total / questions : total;
                double eff = Math.max(0, 1.0 - (avgAtt - 1) / 20.0);
                double m = 0.6 * correctRate + 0.2 * 1.0 + 0.2 * eff; // 简化：无compile_error
                expMastery.put(e.getKey(), Math.round(m * 1000.0) / 10.0);
            }

            // 维度分数
            Map<String, Double> dimScores = new LinkedHashMap<>();
            double totalScore = 0;
            int dimCount = 0;
            for (var dim : skillTreeConfig.getDimensions().entrySet()) {
                double sum = 0; int cnt = 0;
                for (int eid : dim.getValue()) {
                    if (expMastery.containsKey(eid)) { sum += expMastery.get(eid); cnt++; }
                }
                double avg = cnt > 0 ? sum / cnt : 0;
                dimScores.put(dim.getKey(), Math.round(avg * 10.0) / 10.0);
                totalScore += avg;
                dimCount++;
            }
            studentDimScores.put(sid, dimScores);
            studentOverallScores.put(sid, dimCount > 0 ? Math.round(totalScore / dimCount * 10.0) / 10.0 : 0);
        }

        // 1. 班级各维度平均分
        Map<String, Double> classDimAvg = new LinkedHashMap<>();
        Map<String, Double> classDimMin = new LinkedHashMap<>();
        Map<String, Integer> classDimWeakCount = new LinkedHashMap<>();
        for (String dim : skillTreeConfig.getDimensions().keySet()) {
            double sum = 0; int cnt = 0; double min = 100; int weakCnt = 0;
            for (var ds : studentDimScores.values()) {
                double v = ds.getOrDefault(dim, 0.0);
                sum += v; cnt++;
                if (v < min) min = v;
                if (v < 40) weakCnt++;
            }
            classDimAvg.put(dim, cnt > 0 ? Math.round(sum / cnt * 10.0) / 10.0 : 0);
            classDimMin.put(dim, min);
            classDimWeakCount.put(dim, weakCnt);
        }

        // 2. 薄弱维度排行（按低分人数占比排序）
        List<Map<String, Object>> weakRanking = new ArrayList<>();
        int totalStudents = studentDimScores.size();
        for (String dim : skillTreeConfig.getDimensions().keySet()) {
            Map<String, Object> wr = new LinkedHashMap<>();
            wr.put("dimension", dim);
            wr.put("avgScore", classDimAvg.get(dim));
            wr.put("weakCount", classDimWeakCount.get(dim));
            wr.put("weakRatio", totalStudents > 0 ? Math.round((double) classDimWeakCount.get(dim) / totalStudents * 1000.0) / 10.0 : 0);
            weakRanking.add(wr);
        }
        weakRanking.sort((a, b) -> Double.compare((double) b.get("weakRatio"), (double) a.get("weakRatio")));

        // 3. ABC分层
        List<Map<String, Object>> tierA = new ArrayList<>();
        List<Map<String, Object>> tierB = new ArrayList<>();
        List<Map<String, Object>> tierC = new ArrayList<>();
        for (var entry : studentOverallScores.entrySet()) {
            String sid = entry.getKey();
            double score = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("studentId", sid);
            info.put("studentName", studentNames.get(sid));
            info.put("overallScore", score);
            if (score >= 70) tierA.add(info);
            else if (score >= 40) tierB.add(info);
            else tierC.add(info);
        }
        tierA.sort((a, b) -> Double.compare((double) b.get("overallScore"), (double) a.get("overallScore")));
        tierB.sort((a, b) -> Double.compare((double) b.get("overallScore"), (double) a.get("overallScore")));
        tierC.sort((a, b) -> Double.compare((double) b.get("overallScore"), (double) a.get("overallScore")));

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("A", Map.of("label", "优秀 (≥70)", "count", tierA.size(), "students", tierA));
        tiers.put("B", Map.of("label", "中等 (40-69)", "count", tierB.size(), "students", tierB));
        tiers.put("C", Map.of("label", "需关注 (<40)", "count", tierC.size(), "students", tierC));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", className);
        result.put("totalStudents", totalStudents);
        result.put("dimensionAvg", classDimAvg);
        result.put("weakRanking", weakRanking);
        result.put("tiers", tiers);
        result.put("dimensions", new ArrayList<>(skillTreeConfig.getDimensions().keySet()));
        return result;
    }

    // ========== 技能树接口 ==========

    @Cacheable(value = "skillTree")
    public Map<String, Object> getSkillTreeConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> tree = new ArrayList<>();
        for (var entry : skillTreeConfig.getDimensions().entrySet()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("dimension", entry.getKey());
            node.put("description", skillTreeConfig.getDescriptions().get(entry.getKey()));
            List<Map<String, Object>> children = new ArrayList<>();
            for (int eid : entry.getValue()) {
                children.add(Map.of(
                        "experimentId", eid,
                        "name", skillTreeConfig.getExperimentName(eid)
                ));
            }
            node.put("experiments", children);
            tree.add(node);
        }
        result.put("skillTree", tree);
        result.put("totalDimensions", skillTreeConfig.getDimensions().size());
        result.put("totalExperiments", skillTreeConfig.getExperimentNames().size());
        return result;
    }
}
