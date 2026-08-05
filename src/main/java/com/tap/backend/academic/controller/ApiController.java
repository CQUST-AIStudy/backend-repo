package com.tap.backend.academic.controller;

import com.tap.backend.academic.dao.SubmissionDao;
import com.tap.backend.academic.entity.*;
import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.academic.security.StudentSessionResolver;
import com.tap.backend.academic.security.TeacherSessionResolver;
import com.tap.backend.academic.dao.teacherexperiment.TeacherExperimentQueryDao;
import com.tap.backend.academic.service.*;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.learningtracking.LearningTrackingResponse;
import com.tap.backend.academic.teacherexperiment.TeacherStudentAssignmentRow;
import com.tap.backend.academic.teacherexperiment.TeacherSubmissionProblemRow;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.tomcat.util.net.openssl.ciphers.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.awt.Color.red;

@RestController
public class ApiController {

    @Autowired
    private SubmissionDao submissionDao;

    @Autowired
    private TeacherExperimentQueryDao teacherExperimentQueryDao;

    @Autowired
    private StudentCodeService  studentCodeService;

    @Autowired
    private AIRemarksService aiRemarksService;

    @Autowired
    private ExperimentService experimentService;

    @Autowired
    private AiReportService aiReportService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private ScoreService scoreService;

    @Autowired
    private ErrorAnalysisService errorAnalysisService;

    @Autowired
    private LearningTrackingService learningTrackingService;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AISuggestedProblemService aiSuggestedProblemService;

    @Autowired
    private com.tap.backend.academic.service.ProfileService profileService;

    @Autowired
    private StudentSessionResolver studentSessionResolver;

    @Autowired
    private LegacySessionAccessResolver legacySessionAccessResolver;

    @Autowired
    private TeacherSessionResolver teacherSessionResolver;

    @PersistenceContext
    private EntityManager em;

    @Value("${pta.problem-set-base-url}")
    private String ptaProblemSetBaseUrl;

    @Autowired
    @Qualifier("intelligentRecommendationService")
    private LeetCodeRecommendationService leetCodeRecommendationService;

    @Autowired
    private LeetCodeSyncService leetCodeSyncService;

    private static final String LEETCODE_CLEANED_DATA_PATH = "datasets/leetcode/solutions_cleaned.json";
    private volatile boolean leetCodeDataWarmupAttempted = false;

    @Value("${tap.ai.openai.api-key:}")
    private String deepseekApiKey;

    @Value("${tap.ai.openai.base-url:https://api.deepseek.com/v1}")
    private String deepseekBaseUrl;

    @Value("${tap.ai.openai.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${tap.teacher.read-path.unified-submission-detail-enabled:true}")
    private boolean unifiedSubmissionDetailEnabled;

    @Value("${tap.teacher.read-path.submission-detail-legacy-code-fallback-enabled:true}")
    private boolean submissionDetailLegacyCodeFallbackEnabled;

    @Value("${tap.teacher.read-path.submission-detail-legacy-report-fallback-enabled:true}")
    private boolean submissionDetailLegacyReportFallbackEnabled;

    @Value("${tap.teacher.read-path.submission-detail-legacy-ai-remarks-fallback-enabled:true}")
    private boolean submissionDetailLegacyAiRemarksFallbackEnabled;

    private static final Gson gsonInstance = new Gson();

    private final OkHttpClient aiHttpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    @PostMapping("/api/experiments/{id}/report/generate")
    public ResponseEntity<Map<String, Object>> generateExperimentReport(
            @PathVariable int id,
            @RequestBody(required = false) Map<String, Object> userData,
            HttpServletRequest request) {
        String studentNo = studentSessionResolver.requireStudentId(request);
        AiReportResult result = aiReportService.generate(
                studentNo,
                id,
                userData == null ? Collections.emptyMap() : userData);
        return ResponseEntity.ok(toReportResponse(result));
    }

    @GetMapping("/api/experiments/{id}/report")
    public ResponseEntity<Map<String, Object>> getExperimentReport(
            @PathVariable int id,
            HttpServletRequest request) {
        String studentNo = studentSessionResolver.requireStudentId(request);
        return ResponseEntity.ok(toReportResponse(aiReportService.get(studentNo, id)));
    }

    private Map<String, Object> toReportResponse(AiReportResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.success());
        response.put("message", result.message());
        response.put("report", result.report());
        response.put("data", result.data());
        return response;
    }

    /**
     * 将Markdown格式的文本转换为HTML
     * @param markdown Markdown格式的文本
     * @return HTML格式的文本
     */
    private String convertMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }

        String html = markdown;

        // 处理标题
        html = html.replaceAll("# (.*?)(?=\\n|$)", "<h1 style=\"color: skyblue;\">$1</h1>");
        html = html.replaceAll("## (.*?)(?=\\n|$)", "<h2>$1</h2>");
        html = html.replaceAll("### (.*?)(?=\\n|$)", "<h3>$1</h3>");
        html = html.replaceAll("#### (.*?)(?=\\n|$)", "<h4>$1</h4>");

        // 处理粗体和斜体
        html = html.replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("\\*(.*?)\\*", "<em>$1</em>");

        // 处理列表
        Pattern listPattern = Pattern.compile("^(\\d+\\. .*)$|^(- .*)$", Pattern.MULTILINE);
        Matcher listMatcher = listPattern.matcher(html);
        StringBuffer listBuffer = new StringBuffer();

        while (listMatcher.find()) {
            String listItem = listMatcher.group();
            if (listItem.startsWith("- ")) {
                // 无序列表
                listMatcher.appendReplacement(listBuffer, "<li>" + listItem.substring(2) + "</li>");
            } else {
                // 有序列表
                listMatcher.appendReplacement(listBuffer, "<li>" + listItem.substring(listItem.indexOf(' ') + 1) + "</li>");
            }
        }
        listMatcher.appendTail(listBuffer);
        html = listBuffer.toString();

        // 将连续的列表项包装在<ul>或<ol>标签中
        html = html.replaceAll("(<li>.*?</li>)\\n(<li>.*?</li>)", "$1$2");
        html = html.replaceAll("(<li>\\d+\\..*?</li>)+", "<ol>$0</ol>");
        html = html.replaceAll("(<li>[^\\d].*?</li>)+", "<ul>$0</ul>");

        // 处理代码块
        StringBuffer codeBuffer = new StringBuffer();
        Pattern codePattern = Pattern.compile("```([\\s\\S]*?)```");
        Matcher codeMatcher = codePattern.matcher(html);

        while (codeMatcher.find()) {
            String codeContent = codeMatcher.group(1);
            codeMatcher.appendReplacement(codeBuffer,
                "<pre><code>" + codeContent.replace("$", "\\$") + "</code></pre>");
        }
        codeMatcher.appendTail(codeBuffer);
        html = codeBuffer.toString();

        // 处理行内代码
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");

        // 处理段落和换行
        html = html.replaceAll("(?m)^(?!<[hluoc])(.+)$", "<p>$1</p>");
        html = html.replaceAll("\n\n", "<br>");

        return html;
    }

    private Submission resolveLatestSubmission(String username, Integer studentId, int experimentId) {
        return resolveLatestSubmission(username, studentId == null ? null : String.valueOf(studentId), experimentId);
    }

    private Submission resolveLatestSubmission(String username, String studentIdKey, int experimentId) {
        Submission submission = null;
        if (username != null && !username.isBlank()) {
            submission = submissionDao.findByUsernameAndExperimentId(username, experimentId);
        }
        if (submission == null && studentIdKey != null && !studentIdKey.isBlank()) {
            submission = submissionDao.findByUsernameAndExperimentId(studentIdKey, experimentId);
        }
        return submission;
    }

    /**
     * 从 UserEntity 解析学号（用于 AI 错误分析管线）
     */
    private String resolveStudentNo(UserEntity user) {
        if (user == null) return "";
        String usernum = user.getUsernum();
        if (usernum != null && !usernum.isBlank()) return usernum.trim();
        String username = user.getUsername();
        return username != null ? username.trim() : "";
    }

    private String resolveStudentCodeText(Integer studentId, int experimentId) {
        if (studentId == null) {
            return "";
        }
        try {
            StudentCode studentCode = studentId == null
                    ? null
                    : studentCodeService.findCodeByStudentIdAndExperimentId(studentId, experimentId);
            if (studentCode == null || studentCode.getCode() == null) {
                return "";
            }
            return studentCode.getCode();
        } catch (Exception e) {
            System.out.println("获取学生代码失败, studentId=" + studentId + ", experimentId=" + experimentId + ", message=" + e.getMessage());
            return "";
        }
    }

    /**
     * Fallback: 从 artifact 表获取学生代码（student_profile → student_problem_state → artifact）
     */
    private String resolveStudentCodeFromArtifact(String studentNo, int experimentId) {
        if (studentNo == null || studentNo.isBlank()) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT a.text_content " +
                            "FROM student_profile sp " +
                            "JOIN student_problem_state sps ON sps.student_id = sp.id " +
                            "JOIN artifact a ON a.id = sps.latest_code_artifact_id " +
                            "WHERE sp.student_no = ?1 AND sps.offering_id = ?2 AND a.text_content IS NOT NULL " +
                            "ORDER BY sps.id"
            ).setParameter(1, studentNo).setParameter(2, experimentId).getResultList();

            if (rows.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Object[] row : rows) {
                if (row[0] != null) {
                    sb.append(row[0].toString()).append("\n\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            System.out.println("artifact fallback 获取代码失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * Fallback 2: 直接用学号字符串查询 student_code 表（最可靠的旧表查询方式）
     */
    private String resolveStudentCodeByStudentNo(String studentNo, int experimentId) {
        if (studentNo == null || studentNo.isBlank()) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT code FROM student_code WHERE student_id = ?1 AND experiment_id = ?2"
            ).setParameter(1, studentNo).setParameter(2, experimentId).getResultList();

            if (rows.isEmpty()) {
                return "";
            }
            Object code = rows.get(0)[0];
            return code != null ? code.toString() : "";
        } catch (Exception e) {
            System.out.println("student_code fallback 获取代码失败: " + e.getMessage());
            return "";
        }
    }

    private Integer tryParseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String mapSubmissionDetailStatus(String submissionStatus, Double score, String code) {
        if ("NOT_STARTED".equalsIgnoreCase(submissionStatus) && (code == null || code.isBlank())) {
            return "not_started";
        }
        if ("GRADED".equalsIgnoreCase(submissionStatus) || (score != null && score > 0)) {
            return "graded";
        }
        return (code == null || code.isBlank()) ? "not_started" : "submitted";
    }

    private String buildUnifiedSubmissionCode(List<TeacherSubmissionProblemRow> problemRows) {
        if (problemRows == null || problemRows.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int displayIndex = 1;
        for (TeacherSubmissionProblemRow row : problemRows) {
            if (row == null || row.getCode() == null || row.getCode().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("第").append(displayIndex).append("题如下：\n");
            if (row.getProblemTitle() != null && !row.getProblemTitle().isBlank()) {
                builder.append("// ").append(row.getProblemTitle()).append("\n");
            }
            builder.append(row.getCode().trim());
            displayIndex++;
        }
        return builder.toString();
    }

    /**
     * 构造结构化题目列表，供前端按题展示题目（题号/标题/题面）+ 代码。
     * number 的递增规则与 {@link #buildUnifiedSubmissionCode} 严格一致：
     * 仅对有代码的题目递增序号，空代码题 number 为 null 且不递增，
     * 保证与"完整源码"合并字符串中的"第N题如下"及前端正则兜底解析序号对齐。
     */
    private List<Map<String, Object>> buildSubmissionProblems(List<TeacherSubmissionProblemRow> problemRows) {
        List<Map<String, Object>> problems = new ArrayList<>();
        if (problemRows == null || problemRows.isEmpty()) {
            return problems;
        }
        int displayIndex = 1;
        for (TeacherSubmissionProblemRow row : problemRows) {
            if (row == null) {
                continue;
            }
            String rowCode = row.getCode();
            String trimmedCode = rowCode == null ? "" : rowCode.trim();
            boolean hasCode = !trimmedCode.isEmpty();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("number", hasCode ? displayIndex : null);
            p.put("problemNo", row.getProblemNo());
            p.put("problemTitle", row.getProblemTitle());
            p.put("statementMd", row.getStatementMd());
            p.put("code", trimmedCode);
            p.put("problemId", row.getProblemId());
            if (hasCode) {
                displayIndex++;
            }
            problems.add(p);
        }
        return problems;
    }

    private boolean isMoreCompleteCode(String candidateCode, String currentCode) {
        if (candidateCode == null || candidateCode.isBlank()) {
            return false;
        }
        if (currentCode == null || currentCode.isBlank()) {
            return true;
        }
        return candidateCode.length() > currentCode.length();
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private String extractTeacherComment(String report) {
        if (report == null || report.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?s)## 教师评语\\n(.*?)(?=\\n## |\\z)").matcher(report);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value == null ? null : value.trim();
    }

    private Score resolveLegacyScore(String username, String studentIdKey, Experiment experiment) {
        if (experiment == null) {
            return null;
        }

        Score score = null;
        if (studentIdKey != null && !studentIdKey.isBlank()) {
            score = scoreService.findByUsernameAndExperimentNum(studentIdKey, experiment.getNum());
        }
        if (score == null && username != null && !username.isBlank()) {
            score = scoreService.findByUsernameAndExperimentNum(username, experiment.getNum());
        }
        return score;
    }

    private String resolveLegacySubmissionCode(
            String username,
            String studentIdKey,
            Integer studentId,
            int experimentId) {
        Submission latestSubmission = resolveLatestSubmission(username, studentIdKey, experimentId);
        if (latestSubmission != null && latestSubmission.getCode() != null && !latestSubmission.getCode().isBlank()) {
            return latestSubmission.getCode();
        }
        if (!submissionDetailLegacyCodeFallbackEnabled || studentId == null) {
            return "";
        }
        return resolveStudentCodeText(studentId, experimentId);
    }

    private String resolveLegacySubmissionReport(
            String username,
            String studentIdKey,
            Experiment experiment,
            int experimentId) {
        if (!submissionDetailLegacyReportFallbackEnabled) {
            return null;
        }
        Submission latestSubmission = resolveLatestSubmission(username, studentIdKey, experimentId);
        if (latestSubmission != null && latestSubmission.getReport() != null && !latestSubmission.getReport().isBlank()) {
            return latestSubmission.getReport();
        }
        return null;
    }

    private AIRemarks resolveLegacyAiRemarks(Integer studentId, int experimentId) {
        if (!submissionDetailLegacyAiRemarksFallbackEnabled || studentId == null) {
            return null;
        }
        return aiRemarksService.getAIRemarkByStudentAndExperiment(String.valueOf(studentId), experimentId);
    }

    @GetMapping("/api/experiment")
    public List<Score> getUserScores() {
        String username = "2019443672";
        List<Score> userScores = scoreService.findPerExperimentSumScoresByUsername(username);
        return userScores;
    }


   //根据实验ID和学生id获取当前实验的平均抄袭率
    public double getPlagiarismRate(int studentId, int experimentId) {

        String pla = scoreService.getexperimentPlagiarismRate(studentId, experimentId);
        double averagePlagiarismRate = calculateAveragePlagiarismRate(pla);
        return averagePlagiarismRate;
    }

    @GetMapping("/api/experiments1")
    public List<Experiment> getExperiments() {
        return experimentService.findAllExperiments();
    }

    @PostMapping("/api/experiments")
    public ResponseEntity<Map<String, Object>> createExperiment(@RequestBody ExperimentCreateRequest request) {
        Map<String, Object> response = new HashMap<>();

        if (request == null || isBlank(request.getName())) {
            response.put("success", false);
            response.put("message", "实验名称不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            Experiment experiment = new Experiment();
            experiment.setName(request.getName().trim());
            experiment.setDeadline(trimToNull(request.getDeadline()));
            experiment.setDescribe(trimToNull(request.getDescription()));
            experiment.setRequirements(joinRequirements(request.getRequirements()));
            experiment.setTopic_sum(0);
            experiment.setNum(nextExperimentNum());

            boolean saved = experimentService.saveExperiment(experiment);
            if (!saved) {
                response.put("success", false);
                response.put("message", "创建实验失败");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            response.put("success", true);
            response.put("id", experiment.getExperiment_id());
            response.put("data", experiment);
            response.put("message", "实验创建成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "创建实验失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/api/experiments")
    public ResponseEntity<Map<String, Object>> getExperimentList(
            HttpServletRequest request,
            @RequestParam(required = false) Long classId) {
        if (useUnifiedStudentExperimentReadPath()) {
            return getUnifiedStudentExperimentList(request, true, classId);
        }

        Map<String, Object> response = new HashMap<>();

        System.out.println("获取实验列表方法已启动！！！！！");
        try {
            // 从 Session 中获取当前用户名
            UserEntity currentUser = studentSessionResolver.requireStudent(request);
            String currentUsername;  // 固定用户名用于测试
            currentUsername = currentUser.getUsername();
            String currentStudentId = studentSessionResolver.requireStudentId(request);

            // 如果用户未登录，返回错误信息
            if (currentStudentId == null) {
                response.put("success", false);
                response.put("message", "用户未登录或会话已过期");
                return ResponseEntity.ok(response);
            }

            // 获取所有实验
            String scoreLookupUsername = (currentUsername != null && !currentUsername.isBlank())
                    ? currentUsername
                    : currentStudentId;
            List<Experiment> experiments = experimentService.findAllExperiments();

            // 获取当前用户的所有成绩记录
            Map<Integer, Score> userScoresByExperimentId = scoreService.findPerExperimentSumScoresByUsername(scoreLookupUsername)
                    .stream()
                    .collect(Collectors.toMap(Score::getExperiment_id, score -> score, (existing, replacement) -> existing));
            System.out.println("userScoresByExperimentId:" + userScoresByExperimentId);

            // 调用 StudentController 的方法获取学生ID
            StudentController studentController = applicationContext.getBean(StudentController.class);
            ResponseEntity<Map<String, Object>> studentIdResponse = studentController.findStudentIdByUsername(currentUsername);
            Map<String, Object> studentIdData = studentIdResponse.getBody();

            Integer studentId = null;
            if (studentIdData != null && (Boolean) studentIdData.getOrDefault("success", false)) {
                studentId = (Integer) studentIdData.get("studentId");
                System.out.println("获取到学生ID: " + studentId);
            } else {
                System.out.println("未找到学生ID");
            }

            // 如果用 username 查不到成绩，尝试用 student_id 查（score表中username可能存的是学号）
            studentId = Integer.valueOf(currentStudentId);
            if (userScoresByExperimentId.isEmpty() && studentId != null) {
                String studentIdStr = String.valueOf(studentId);
                userScoresByExperimentId = scoreService.findPerExperimentSumScoresByUsername(studentIdStr)
                        .stream()
                        .collect(Collectors.toMap(Score::getExperiment_id, score -> score, (existing, replacement) -> existing));
                System.out.println("使用studentId查询成绩: " + userScoresByExperimentId);
            }


            // 获取 StudentCodeController 实例
            StudentCodeController studentCodeController = null;

            // 转换实验列表为前端所需的数据格式
            List<Map<String, Object>> experimentDataList = new ArrayList<>();

            for (Experiment experiment : experiments) {
                Map<String, Object> experimentData = new HashMap<>();
                int experimentId = experiment.getExperiment_id();

                // 获取学生的AIRemark
                ResponseEntity<Map<String, Object>> aiRemarkResponse = studentController.getAIRemark(currentStudentId, experimentId);
                Map<String, Object> aiRemarkData = aiRemarkResponse.getBody();

                String aiComment = "暂时还没有生成AI点评哦，请耐心等待.......";
                if (aiRemarkData != null && (Boolean) aiRemarkData.getOrDefault("success", false)) {
                    // 从data字段获取AIRemarks对象
                    Object dataObj = aiRemarkData.get("data");
                    if (dataObj != null && dataObj instanceof com.tap.backend.academic.entity.AIRemarks) {
                        com.tap.backend.academic.entity.AIRemarks aiRemarks = (com.tap.backend.academic.entity.AIRemarks) dataObj;
                        String remarkContent = aiRemarks.getAiremark();
                        if (remarkContent != null && !remarkContent.isEmpty()) {
                            aiComment = remarkContent; // 返回原始Markdown，前端负责渲染
                            System.out.println("获取到学生ID: " + studentId + "，实验ID: " + experimentId + "的AI点评");
                        }
                    }
                } else {
                    System.out.println("未找到学生ID: " + studentId + "，实验ID: " + experimentId + "的AI点评");
                }

                // 基本实验信息
                experimentData.put("id", experimentId);
                experimentData.put("name", experiment.getName());
                experimentData.put("deadline", experiment.getDeadline());
                experimentData.put("description", experiment.getDescribe());

                // 解析requirements字段
                experimentData.put("requirements", parseRequirements(experiment.getRequirements()));

                // 如果成功获取了学生ID，调用 StudentCodeController 获取学生代码
                String studentCode = resolveStudentCodeText(studentId, experimentId);
                if (false) {
                    try {
                        ResponseEntity<Map<String, Object>> codeResponse = studentCodeController.getStudentExperimentCode(studentId, experimentId);
                        Map<String, Object> codeData = codeResponse.getBody();

                        if (codeData != null && (Boolean) codeData.getOrDefault("success", false)) {
                            // 修复这里的类型转换问题 - StudentCode对象不能直接转换为Map
                            Object codeObj = codeData.get("code");
                            System.out.println("获取到的代码对象类型: " + (codeObj != null ? codeObj.getClass().getName() : "null"));

                            if (codeObj instanceof com.tap.backend.academic.entity.StudentCode) {
                                // 正确处理StudentCode对象
                                com.tap.backend.academic.entity.StudentCode studentCodeObj = (com.tap.backend.academic.entity.StudentCode) codeObj;
                                studentCode = studentCodeObj.getCode();
                                System.out.println("获取到学生代码，长度: " + (studentCode != null ? studentCode.length() : 0));
                            } else if (codeObj instanceof Map) {
                                // 如果返回的是Map，也处理一下
                                @SuppressWarnings("unchecked")
                                Map<String, Object> codeMap = (Map<String, Object>) codeObj;
                                if (codeMap.containsKey("code")) {
                                    studentCode = (String) codeMap.get("code");
                                    System.out.println("从Map获取到学生代码，长度: " + (studentCode != null ? studentCode.length() : 0));
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.out.println("获取学生代码时出错: " + e.getMessage());
                    }
                }

                // 设置代码及AI点评内容
                experimentData.put("code", studentCode);
                experimentData.put("aiComment", aiComment);



//                // 获取学生的实验报告
//                if (studentId != null) {
//                    try {
//                        // 注意这里需要注入ReportService
//                        ReportService reportService = applicationContext.getBean(ReportService.class);
//                        ExperimentReport latestReport = reportService.getLatestReportForExperiment(studentId.toString(), experimentId);
//
//                        if (latestReport != null) {
//                            // 报告存在，转换为Base64字符串以便前端处理
//                            String reportBase64 = Base64.getEncoder().encodeToString(latestReport.getReportData());
//                            experimentData.put("report", reportBase64);
//                            experimentData.put("reportName", latestReport.getReportName());
//                            experimentData.put("reportId", latestReport.getReportId());
//                            experimentData.put("reportTime", latestReport.getGeneratedTime());
//                        } else {
//                            // 报告不存在
//                            experimentData.put("report", null);
//                            experimentData.put("reportName", null);
//                            experimentData.put("reportId", null);
//                            experimentData.put("reportTime", null);
//                        }
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                        System.out.println("获取学生报告时出错: " + e.getMessage());
//                        experimentData.put("report", null);
//                    }
//                } else {
//                    experimentData.put("report", null);
//                }

                // 获取当前用户的提交信息
                Submission latestSubmission = resolveLatestSubmission(currentUsername, studentId, experimentId);
                String latestReport = latestSubmission != null && latestSubmission.getReport() != null
                        ? latestSubmission.getReport()
                        : null;
                experimentData.put("report", latestReport);
                experimentData.put("reportStatus", latestReport == null ? "missing" : "submitted");
                experimentData.put("teacherComment", extractTeacherComment(latestReport));
                Score userScore = userScoresByExperimentId.get(experimentId);

                if (userScore != null) {
                    experimentData.put("status", userScore.getStatus() == null
                            ? "submitted" : userScore.getStatus());
                    experimentData.put("submitTime", userScore.getSubmit_time());
                    experimentData.put("score", userScore.getScore());
                    experimentData.put("plagiarismRate", userScore.getPlagiarism_rate() == null
                            ? null
                            : Math.round(getPlagiarismRate(studentId, experimentId) * 100) / 100.0);
                } else {
                    experimentData.put("status",
                            latestSubmission != null || (studentCode != null && !studentCode.isBlank())
                                    ? "submitted"
                                    : "not_started");
                    experimentData.put("submitTime", null);
                    experimentData.put("score", null);
                    experimentData.put("plagiarismRate", null);
                }

                experimentDataList.add(experimentData);
            }

            response.put("success", true);
            response.put("data", experimentDataList);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("data", new ArrayList<>());
            response.put("source", "degraded_empty");
            response.put("message", "获取实验列表失败: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    // 新方法，根据experiment_id查找数据
    @GetMapping("/api/experiments/{experimentId}")
    public ResponseEntity<Map<String, Object>> getExperimentById(
            @PathVariable int experimentId,
            HttpServletRequest request,
            @RequestParam(required = false) Long classId) {
        // 不从列表里过滤，直接用 excludeRecommended=false 查询，确保推荐题目集也能找到
        ResponseEntity<Map<String, Object>> allExperimentsResponse =
                getUnifiedStudentExperimentList(request, false, classId);
        Map<String, Object> allExperimentsData = allExperimentsResponse.getBody();

        if (allExperimentsData != null && allExperimentsData.containsKey("data")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> experimentDataList = (List<Map<String, Object>>) allExperimentsData.get("data");
            Optional<Map<String, Object>> targetExperiment = experimentDataList.stream()
                    .filter(data -> idsEqual(data.get("id"), experimentId))
                    .findFirst();

            if (targetExperiment.isPresent()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", targetExperiment.get());
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "未找到指定 experiment_id 的实验数据");
                return ResponseEntity.ok(response);
            }
        } else {
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", new ArrayList<>());
            response.put("message", "获取实验列表时出错");
            return ResponseEntity.ok(response);
        }
    }

    private boolean useUnifiedStudentExperimentReadPath() {
        return true;
    }

    private ResponseEntity<Map<String, Object>> getUnifiedStudentExperimentList(
            HttpServletRequest request,
            boolean excludeRecommended,
            Long classId) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            UserEntity currentUser = studentSessionResolver.requireStudent(request);
            String studentNo = studentSessionResolver.requireStudentId(request);

            String excludeClause = excludeRecommended
                    ? "AND at.title NOT LIKE '%推荐题目集%' " +
                      "AND COALESCE(NULLIF(ao.title_override, ''), at.title) NOT LIKE '%推荐题目集%' "
                    : "";
            String programmingDataClause = excludeRecommended
                    ? "AND EXISTS (" +
                      "SELECT 1 FROM assignment_problem eligible_ap " +
                      "JOIN pta_problem_detail eligible_pd " +
                      "  ON eligible_pd.problem_set_id = ao.pta_problem_set_id " +
                      " AND eligible_pd.problem_set_problem_id = eligible_ap.source_problem_id " +
                      "WHERE eligible_ap.offering_id = ao.id " +
                      "  AND eligible_ap.status = 'ACTIVE' " +
                      "  AND UPPER(TRIM(COALESCE(eligible_pd.problem_type, ''))) = 'PROGRAMMING'" +
                      ") "
                    : "";

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT ao.id, ao.template_id, ao.class_id, tc.name, tc.class_code, " +
                            "COALESCE(NULLIF(ao.title_override, ''), at.title) AS title, " +
                            "ao.deadline_at, at.description_md, ao.status, " +
                            "sa.submission_status, sa.first_submit_at, sa.last_submit_at, " +
                            "sa.accepted_problem_count, sa.submitted_problem_count, sa.problem_count, " +
                            "sa.best_total_score, sa.latest_total_score, sp.student_no, sp.real_name, sp.id, " +
                            "latest_attempt.latest_submit_at " +
                            "FROM student_profile sp " +
                            "JOIN class_member cm ON cm.student_id = sp.id AND cm.member_status = 'ACTIVE' " +
                            "JOIN teaching_class tc ON tc.id = cm.class_id " +
                            "JOIN assignment_offering ao ON ao.class_id = cm.class_id " +
                            "JOIN assignment_template at ON at.id = ao.template_id " +
                            "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id AND sa.student_id = sp.id " +
                            "LEFT JOIN (" +
                            "SELECT spa.offering_id, spa.student_id, MAX(spa.submitted_at) AS latest_submit_at " +
                            "FROM student_problem_attempt spa " +
                            "GROUP BY spa.offering_id, spa.student_id" +
                            ") latest_attempt ON latest_attempt.offering_id = ao.id " +
                            "AND latest_attempt.student_id = sp.id " +
                            "WHERE sp.student_no = ?1 " +
                             "AND (?2 IS NULL OR cm.class_id = ?2) " +
                             "AND (tc.status IS NULL OR tc.status = 'ACTIVE') " +
                             "AND ao.status <> 'ARCHIVED' " +
                             programmingDataClause +
                             excludeClause +
                            "ORDER BY tc.id, COALESCE(ao.seq_no, 999999), ao.id"
            ).setParameter(1, studentNo)
                    .setParameter(2, classId)
                    .getResultList();

            List<Map<String, Object>> experiments = new ArrayList<>();
            for (Object[] row : rows) {
                Long offeringId = toLong(row[0]);
                String description = toStringValue(row[7]);
                String submissionStatus = toStringValue(row[9]);
                int submittedProblemCount = toInt(row[13]);
                Double score = firstNonNull(toDouble(row[16]), toDouble(row[15]), 0.0);

                Map<String, Object> experimentData = new LinkedHashMap<>();
                experimentData.put("id", offeringId);
                experimentData.put("offeringId", offeringId);
                experimentData.put("templateId", toLong(row[1]));
                experimentData.put("classId", toLong(row[2]));
                experimentData.put("className", row[3]);
                experimentData.put("classCode", row[4]);
                experimentData.put("studentProfileId", toLong(row[19]));
                experimentData.put("studentNo", row[17]);
                experimentData.put("studentName", row[18]);
                experimentData.put("name", row[5]);
                experimentData.put("deadline", formatDateTime(row[6]));
                experimentData.put("description", description);
                experimentData.put("requirements", parseRequirements(description));
                experimentData.put("code", "");
                experimentData.put("aiComment", "");
                experimentData.put("report", "");
                experimentData.put("teacherComment", null);
                experimentData.put("status", mapStudentAssignmentStatus(submissionStatus, submittedProblemCount, score));
                experimentData.put("submitTime", formatDateTime(
                        row[20] != null ? row[20] : (row[11] != null ? row[11] : row[10])));
                experimentData.put("score", roundTwoDecimals(score));
                experimentData.put("plagiarismRate", 0.0);
                experimentData.put("acceptedProblemCount", toInt(row[12]));
                experimentData.put("submittedProblemCount", submittedProblemCount);
                experimentData.put("problemCount", toInt(row[14]));
                experiments.add(experimentData);
            }

            // Batch-fetch latest code for each experiment from artifact table
            if (!experiments.isEmpty()) {
                List<Long> offeringIds = experiments.stream()
                        .map(e -> (Long) e.get("offeringId"))
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());

                if (!offeringIds.isEmpty()) {
                    StringBuilder codeSql = new StringBuilder(
                            "SELECT sps.offering_id, ap.problem_no, ap.title, apd.content, " +
                                    "CAST(ap.sort_order AS SIGNED) AS sort_order, a.text_content " +
                                    "FROM student_problem_state sps " +
                                    "JOIN student_profile sp ON sp.id = sps.student_id " +
                                    "JOIN assignment_problem ap ON ap.id = sps.problem_id " +
                                    "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no " +
                                    "LEFT JOIN artifact a ON a.id = sps.latest_code_artifact_id " +
                                    "WHERE sp.student_no = ?1 AND sps.offering_id IN ("
                    );
                    for (int i = 0; i < offeringIds.size(); i++) {
                        if (i > 0) codeSql.append(",");
                        codeSql.append("?").append(i + 2);
                    }
                    codeSql.append(") ORDER BY sps.offering_id, ap.sort_order, ap.id");

                    jakarta.persistence.Query codeQuery = em.createNativeQuery(codeSql.toString());
                    codeQuery.setParameter(1, studentNo);
                    for (int i = 0; i < offeringIds.size(); i++) {
                        codeQuery.setParameter(i + 2, offeringIds.get(i));
                    }

                    @SuppressWarnings("unchecked")
                    List<Object[]> codeRows = codeQuery.getResultList();

                    // 按题分组：每题一个结构化对象（题号/标题/题面/代码），同时保留合并 code 字符串兼容旧逻辑
                    Map<Long, List<Map<String, Object>>> problemsMap = new LinkedHashMap<>();
                    Map<Long, StringBuilder> codeMap = new LinkedHashMap<>();
                    for (Object[] cr : codeRows) {
                        Long oid = toLong(cr[0]);
                        String problemNo = cr[1] != null ? cr[1].toString() : null;
                        String title = cr[2] != null ? cr[2].toString() : null;
                        String statementMd = cr[3] != null ? cr[3].toString() : null;
                        String codeText = cr[5] != null ? cr[5].toString() : "";

                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("problemNo", problemNo);
                        p.put("problemTitle", title);
                        p.put("statementMd", statementMd);
                        p.put("code", codeText.trim());
                        problemsMap.computeIfAbsent(oid, k -> new ArrayList<>()).add(p);

                        // 兼容：仅非空代码拼入合并 code 字符串
                        if (!codeText.isBlank()) {
                            codeMap.computeIfAbsent(oid, k -> new StringBuilder())
                                    .append(codeText).append("\n\n");
                        }
                    }

                    // 合并代码与题目列表到每个实验
                    for (Map<String, Object> exp : experiments) {
                        Long oid = (Long) exp.get("offeringId");
                        List<Map<String, Object>> probs = problemsMap.get(oid);
                        if (probs != null) {
                            // 补展示序号（按 sort_order 顺序）
                            int idx = 1;
                            for (Map<String, Object> p : probs) {
                                p.put("number", idx++);
                            }
                            exp.put("problems", probs);
                        } else {
                            exp.put("problems", new ArrayList<>());
                        }
                        StringBuilder sb = codeMap.get(oid);
                        if (sb != null && sb.length() > 0) {
                            exp.put("code", sb.toString().trim());
                        }
                    }
                }

                // Legacy fallback: query student_code table for experiments still missing code
                List<Long> stillEmpty = experiments.stream()
                        .filter(e -> {
                            Object code = e.get("code");
                            Object probs = e.get("problems");
                            boolean noCode = code == null || code.toString().isEmpty();
                            boolean noProblems = probs == null || ((List<?>) probs).isEmpty();
                            return noCode && noProblems;
                        })
                        .map(e -> (Long) e.get("offeringId"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                if (!stillEmpty.isEmpty()) {
                    StringBuilder legacySql = new StringBuilder(
                            "SELECT experiment_id, code FROM student_code WHERE student_id = ?1 AND experiment_id IN ("
                    );
                    for (int i = 0; i < stillEmpty.size(); i++) {
                        if (i > 0) legacySql.append(",");
                        legacySql.append("?").append(i + 2);
                    }
                    legacySql.append(")");

                    jakarta.persistence.Query legacyQuery = em.createNativeQuery(legacySql.toString());
                    legacyQuery.setParameter(1, studentNo);
                    for (int i = 0; i < stillEmpty.size(); i++) {
                        legacyQuery.setParameter(i + 2, stillEmpty.get(i));
                    }

                    @SuppressWarnings("unchecked")
                    List<Object[]> legacyRows = legacyQuery.getResultList();
                    for (Object[] lr : legacyRows) {
                        Long expId = toLong(lr[0]);
                        String code = lr[1] != null ? lr[1].toString() : "";
                        for (Map<String, Object> exp : experiments) {
                            if (expId.equals(exp.get("offeringId")) && code.length() > 0) {
                                exp.put("code", code);
                                Object existingProblems = exp.get("problems");
                                if (existingProblems == null || ((List<?>) existingProblems).isEmpty()) {
                                    Map<String, Object> fallbackProblem = new LinkedHashMap<>();
                                    fallbackProblem.put("number", 1);
                                    fallbackProblem.put("problemNo", null);
                                    fallbackProblem.put("problemTitle", null);
                                    fallbackProblem.put("statementMd", null);
                                    fallbackProblem.put("code", code);
                                    List<Map<String, Object>> list = new ArrayList<>();
                                    list.add(fallbackProblem);
                                    exp.put("problems", list);
                                }
                                break;
                            }
                        }
                    }
                }
            }

            // 补查 assignment_problem 题目信息：为缺少标题/题面的 problem 填充数据
            try {
                Set<Long> allOfferingIds = experiments.stream()
                        .map(e -> (Long) e.get("offeringId"))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                if (!allOfferingIds.isEmpty()) {
                    StringBuilder enrichSql = new StringBuilder(
                            "SELECT ao.id AS offering_id, ap.problem_no, ap.title, " +
                            "COALESCE(apd.content, ap.statement_md) AS statement_md, " +
                            "CAST(ap.sort_order AS SIGNED) AS sort_order " +
                            "FROM assignment_offering ao " +
                            "JOIN assignment_problem ap ON ap.offering_id = ao.id " +
                            "LEFT JOIN pta_problem_detail apd ON apd.problem_set_problem_id = ap.problem_no " +
                            "WHERE ao.id IN ("
                    );
                    int idx = 0;
                    List<Long> oidList = new ArrayList<>(allOfferingIds);
                    for (int i = 0; i < oidList.size(); i++) {
                        if (i > 0) enrichSql.append(",");
                        enrichSql.append("?").append(i + 1);
                    }
                    enrichSql.append(") ORDER BY ao.id, ap.sort_order");

                    jakarta.persistence.Query enrichQuery = em.createNativeQuery(enrichSql.toString());
                    for (int i = 0; i < oidList.size(); i++) {
                        enrichQuery.setParameter(i + 1, oidList.get(i));
                    }

                    @SuppressWarnings("unchecked")
                    List<Object[]> enrichRows = enrichQuery.getResultList();
                    Map<Long, List<Map<String, Object>>> dbProblemsMap = new LinkedHashMap<>();
                    for (Object[] er : enrichRows) {
                        Long oid = toLong(er[0]);
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("problemNo", er[1] != null ? er[1].toString() : null);
                        info.put("problemTitle", er[2] != null ? er[2].toString() : null);
                        info.put("statementMd", er[3] != null ? er[3].toString() : null);
                        dbProblemsMap.computeIfAbsent(oid, k -> new ArrayList<>()).add(info);
                    }

                    // 按 number 将 DB 的题目信息合并入 experiments 的 problems
                    for (Map<String, Object> exp : experiments) {
                        Long oid = (Long) exp.get("offeringId");
                        List<Map<String, Object>> dbList = dbProblemsMap.get(oid);
                        if (dbList == null || dbList.isEmpty()) continue;

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> problems = (List<Map<String, Object>>) exp.get("problems");
                        if (problems == null) problems = new ArrayList<>();

                        for (int i = 0; i < problems.size() && i < dbList.size(); i++) {
                            Map<String, Object> p = problems.get(i);
                            Map<String, Object> db = dbList.get(i);
                            // 只在为空时才补值
                            if (p.get("problemTitle") == null || "".equals(p.get("problemTitle"))) {
                                p.put("problemTitle", db.get("problemTitle"));
                            }
                            if (p.get("statementMd") == null || "".equals(p.get("statementMd"))) {
                                p.put("statementMd", db.get("statementMd"));
                            }
                            if (p.get("problemNo") == null || "".equals(p.get("problemNo"))) {
                                p.put("problemNo", db.get("problemNo"));
                            }
                        }
                        // 如果 problems 数量不够 DB 的数量，补上缺少的
                        for (int i = problems.size(); i < dbList.size(); i++) {
                            Map<String, Object> newP = new LinkedHashMap<>();
                            newP.put("number", i + 1);
                            newP.put("code", "");
                            newP.put("problemNo", dbList.get(i).get("problemNo"));
                            newP.put("problemTitle", dbList.get(i).get("problemTitle"));
                            newP.put("statementMd", dbList.get(i).get("statementMd"));
                            newP.put("testResults", null);
                            problems.add(newP);
                        }
                        exp.put("problems", problems);
                    }
                }
            } catch (Exception e) {
                System.err.println("[WARN] 补查题目信息失败（不影响主流程）: " + e.getMessage());
            }

            // 解析合并代码中的测试点，按题注入到 problems 数组
            for (Map<String, Object> exp : experiments) {
                String code = Objects.toString(exp.get("code"), "").trim();
                enrichProblemsWithTestPoints(exp, code);
            }

            for (Map<String, Object> exp : experiments) {
                String code = Objects.toString(exp.get("code"), "").trim();
                boolean eligible = !code.isBlank();
                exp.put("aiReportEligible", eligible);
                exp.put("aiReportIneligibleReason", eligible ? "" : "该实验尚无平台 OJ 代码提交，暂不能生成 AI 报告");
            }

            response.put("success", true);
            response.put("data", experiments);
            response.put("source", "unified_academic");
            response.put("studentUsername", currentUser.getUsername());
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", true);
            response.put("data", new ArrayList<>());
            response.put("source", "degraded_empty");
            response.put("message", "failed to load student experiments: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 从合并代码字符串中解析每题代码 + 全部测试结果（去重），
     * 按题号注入到 problems，测试点只放在第一题和实验级字段
     */
    private void enrichProblemsWithTestPoints(Map<String, Object> exp, String code) {
        if (code == null || code.isBlank()) return;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> problems = (List<Map<String, Object>>) exp.get("problems");
        if (problems == null) problems = new ArrayList<>();

        // 找所有 "第N题如下" 标记
        java.util.regex.Matcher markerMatcher = java.util.regex.Pattern.compile("第(\\d+)题如下[：:]").matcher(code);
        java.util.List<int[]> markers = new java.util.ArrayList<>(); // [start, end, number]
        while (markerMatcher.find()) {
            markers.add(new int[]{markerMatcher.start(), markerMatcher.end(), Integer.parseInt(markerMatcher.group(1))});
        }

        if (markers.isEmpty()) return;

        // 每题只取纯代码（去掉测试点表格行）
        for (int i = 0; i < markers.size(); i++) {
            int bodyStart = markers.get(i)[1];
            int bodyEnd = (i + 1 < markers.size()) ? markers.get(i + 1)[0] : code.length();
            String body = code.substring(bodyStart, bodyEnd);
            int questionNum = markers.get(i)[2];

            // 找代码结束位置：第一个 |...测试点...| 表头行
            StringBuilder cleanCode = new StringBuilder();
            for (String line : body.split("\n")) {
                if (line.matches(".*\\|.*测试点.*\\|.*")) break;
                cleanCode.append(line).append("\n");
            }
            String qCode = cleanCode.toString().trim();

            // 找到或创建 problem
            Map<String, Object> target = null;
            for (Map<String, Object> p : problems) {
                if (Integer.valueOf(questionNum).equals(p.get("number"))) {
                    target = p;
                    break;
                }
            }
            if (target == null && questionNum <= problems.size()) {
                target = problems.get(questionNum - 1);
            }
            if (target != null) {
                target.put("code", qCode);
                target.put("number", questionNum);
                // 测试点不放每题，避免重复
                target.remove("testResults");
            } else if (!qCode.isEmpty()) {
                Map<String, Object> np = new LinkedHashMap<>();
                np.put("number", questionNum);
                np.put("code", qCode);
                problems.add(np);
            }
        }

        // 从第一题拆测试点表格，轮流分配：第1个|测试点|表→Q1，第2个→Q2...轮流
        if (markers.size() >= 1) {
            int firstBodyStart = markers.get(0)[1];
            int firstBodyEnd = markers.size() > 1 ? markers.get(1)[0] : code.length();
            String firstBody = code.substring(firstBodyStart, firstBodyEnd);

            // 拆成独立表格（每个 |...测试点...| 表头开始一个新表格）
            java.util.List<java.util.List<Map<String, Object>>> tables = new java.util.ArrayList<>();
            java.util.List<Map<String, Object>> currentTable = null;
            for (String line : firstBody.split("\n")) {
                if (line.matches(".*\\|.*测试点.*\\|.*")) {
                    if (currentTable != null && !currentTable.isEmpty()) tables.add(currentTable);
                    currentTable = new java.util.ArrayList<>();
                    continue;
                }
                if (currentTable == null) continue;
                if (!line.startsWith("|") || line.contains("---") || line.contains("测试点")) continue;
                String[] cells = line.split("\\|");
                java.util.List<String> parts = new java.util.ArrayList<>();
                for (String c : cells) { String v = c.trim(); if (!v.isEmpty()) parts.add(v); }
                if (parts.size() >= 5) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("point", parts.get(0));
                    row.put("result", parts.get(1));
                    row.put("score", parts.get(2));
                    row.put("time", parts.get(3));
                    row.put("memory", parts.get(4));
                    currentTable.add(row);
                }
            }
            if (currentTable != null && !currentTable.isEmpty()) tables.add(currentTable);

            // 轮流分配：table[k] → problems[k % N]
            int N = problems.size();
            if (N > 0 && !tables.isEmpty()) {
                for (int k = 0; k < tables.size(); k++) {
                    int qi = k % N;
                    @SuppressWarnings("unchecked")
                    java.util.List<Map<String, Object>> existing = (java.util.List<Map<String, Object>>) problems.get(qi).get("testResults");
                    if (existing == null) {
                        existing = new java.util.ArrayList<>();
                        problems.get(qi).put("testResults", existing);
                    }
                    existing.addAll(tables.get(k));
                }
            }

            // 同时放一份去重的在实验级
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            java.util.List<Map<String, Object>> allTP = new java.util.ArrayList<>();
            for (Map<String, Object> p : problems) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> tp = (java.util.List<Map<String, Object>>) p.get("testResults");
                if (tp != null) {
                    for (Map<String, Object> row : tp) {
                        String key = row.get("point") + "|" + row.get("result") + "|" + row.get("score");
                        if (seen.add(key)) allTP.add(row);
                    }
                }
            }
            if (!allTP.isEmpty()) exp.put("allTestResults", allTP);
        }

        exp.put("problems", problems);

        // 清理 code：去 | 行
        StringBuilder cleanSb = new StringBuilder();
        for (String line : code.split("\n")) {
            if (!line.trim().startsWith("|")) cleanSb.append(line).append("\n");
        }
        exp.put("code", cleanSb.toString().replaceAll("\n{3,}", "\n\n").trim());
    }

    private boolean idsEqual(Object value, long expected) {
        return value instanceof Number number && number.longValue() == expected;
    }

    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String formatDateTime(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String mapStudentAssignmentStatus(String submissionStatus, int submittedProblemCount, Double score) {
        if ("GRADED".equalsIgnoreCase(submissionStatus) || (score != null && score > 0)) {
            return "completed";
        }
        if ("SUBMITTED".equalsIgnoreCase(submissionStatus) || "IN_PROGRESS".equalsIgnoreCase(submissionStatus)
                || submittedProblemCount > 0) {
            return "in_progress";
        }
        return "not_started";
    }

    private List<String> parseRequirements(String requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return new ArrayList<>();
        }

        // 这里简单假设requirements是用换行符分隔的，你可以根据实际情况修改
        return Arrays.asList(requirements.split("\\r?\\n"));
    }

    // 计算平均查重率
    private double calculateAveragePlagiarismRate(String plagiarismRates) {
        if (plagiarismRates == null || plagiarismRates.isEmpty()) {
            return 0.0;
        }

        String[] rates = plagiarismRates.split(",");
        double sum = 0.0;
        int count = 0;

        for (String rate : rates) {
            // 跳过"-"值
            if (!rate.trim().equals("-")) {
                try {
                    // 移除百分号并转换为double
                    String cleanRate = rate.replace("%", "").trim();
                    sum += Double.parseDouble(cleanRate);

                } catch (NumberFormatException e) {
                    // 忽略无法解析的值
                    continue;
                }
            }
            count++;
        }

        return count > 0 ? sum / count : 0.0;
    }

    // 检查截止日期是否已过
    private boolean isDeadlinePassed(String deadlineStr) {
        try {
            // 支持多种日期格式解析
            List<String> dateFormats = Arrays.asList("yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy");
            for (String format : dateFormats) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(format);
                    Date deadline = sdf.parse(deadlineStr);
                    if (deadline.before(new Date())) {
                        return true;
                    }
                } catch (Exception e) {
                    // 格式不匹配，继续尝试下一个格式
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/experiments/{id}")
    public ResponseEntity<Map<String, Object>> getExperiment(@PathVariable int id) {
        Map<String, Object> response = new HashMap<>();

        try {
            Experiment experiment = experimentService.findExperimentById(id);

            if (experiment == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // 处理requirements字符串，转换为列表
            if (experiment.getRequirements() != null) {
                List<String> requirementsList = Arrays.asList(experiment.getRequirements().split("\n"));
                experiment.setRequirementsList(requirementsList);
            }

            return ResponseEntity.ok(Map.of(
                    "id", experiment.getExperiment_id(),
                    "name", experiment.getName(),
                    "deadline", experiment.getDeadline(),
                    "description", experiment.getDescribe(),
                    "requirements", experiment.getRequirementsList(),
                    "status", experiment.getStatus(),
                    "submitTime", experiment.getSubmitTime(),
                    "score", experiment.getScore(),
                    "plagiarismRate", experiment.getPlagiarismRate()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @GetMapping("/student/{username}/experiments")
    public ResponseEntity<List<Map<String, Object>>> getStudentExperiments(
            @PathVariable String username,
            HttpServletRequest request
    ) {
        try {
            UserEntity currentUser = studentSessionResolver.requireStudent(request);
            String currentUsername = currentUser.getUsername();
            if (currentUsername == null || currentUsername.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null);
            }
            if (!currentUsername.equals(username)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }
            List<Map<String, Object>> experiments = experimentService.findExperimentsByUsername(currentUsername);
            return ResponseEntity.ok(experiments);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/experiments/{id}/submit")
    public ResponseEntity<Map<String, Object>> submitExperiment(
            @PathVariable int id,
            @RequestParam(required = false) String username,
            @RequestBody Map<String, String> submission,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            UserEntity currentUser = studentSessionResolver.requireStudent(request);
            String currentUsername = currentUser.getUsername();
            if (currentUsername == null || currentUsername.isBlank()) {
                response.put("status", "error");
                response.put("message", "用户未登录");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            if (username != null && !username.isBlank() && !currentUsername.equals(username)) {
                response.put("status", "error");
                response.put("message", "无权提交其他用户的实验");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            String code = submission.get("code");
            String report = submission.get("report");

            boolean success = experimentService.submitExperiment(id, currentUsername, code, report);

            if (success) {
                // 异步触发 AI 错误分析管线（不阻塞响应）
                try {
                    String studentNo = resolveStudentNo(currentUser);
                    String studentName = currentUser.getUsername() != null ? currentUser.getUsername() : currentUsername;
                    errorAnalysisService.triggerAnalysisPipeline(studentNo, studentName, id);
                } catch (Exception ignored) {
                    // 异步触发失败不影响提交结果
                }

                response.put("status", "success");
                response.put("message", "实验提交成功");
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "error");
                response.put("message", "实验提交失败");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "实验提交失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 根据学生ID和实验ID获取该实验的推荐练习
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return 推荐练习列表
     */
    private List<Map<String, Object>> getRecommendedPracticesByExperiment(int studentId, int experimentId) {
        try {
            AISuggestedProblem suggestedProblem = aiSuggestedProblemService.findByStudentIdAndExperimentId(studentId, experimentId);
            if (suggestedProblem != null) {
                List<Map<String, Object>> recommendedPractices = aiSuggestedProblemService.parseRecommendedPractices(suggestedProblem.getContent());
            
            // 处理每个练习，确保数据格式正确
                for (Map<String, Object> practice : recommendedPractices) {
                if (practice.containsKey("type") && "problem".equals(practice.get("type"))) {
                    System.out.println("处理题目: " + practice.get("number") + ". " + practice.get("title"));
                    System.out.println("URL: " + practice.get("url"));
                }
            }
            
            System.out.println("获取到学生ID: " + studentId + "，实验ID: " + experimentId + "的推荐练习，数量: " + recommendedPractices.size());
            return recommendedPractices;
        } else {
            System.out.println("未找到学生ID: " + studentId + "，实验ID: " + experimentId + "的推荐练习");
            return new ArrayList<>();
        }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("鎸夊疄楠岃幏鍙栨帹鑽愮粌涔犲け璐ワ紝闄嶇骇杩斿洖绌哄垪琛? " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 获取学生的所有推荐练习
     * @param studentId 学生ID
     * @return 响应实体，包含所有推荐练习列表
     */
    @GetMapping("/api/student/{studentId}/recommendedPractices")
    public ResponseEntity<Map<String, Object>> getAllRecommendedPracticesByStudent(
            @PathVariable int studentId,
            HttpServletRequest request,
            @RequestParam(required = false) Long classId) {
        String authorizedStudentNo = studentSessionResolver.requireAuthorizedStudentId(String.valueOf(studentId), request);
        return getAllRecommendedPracticesByStudent(authorizedStudentNo, classId);
    }

    /**
     * 获取学生的推荐练习列表（基于PTA数据，筛选标题包含"推荐练习"的作业）
     * @param studentNo 学号
     * @return 响应实体，包含推荐练习列表
     */
    public ResponseEntity<Map<String, Object>> getAllRecommendedPracticesByStudent(
            String studentNo,
            Long classId) {
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT ao.id, ao.template_id, ao.class_id, tc.name, tc.class_code, " +
                            "COALESCE(NULLIF(ao.title_override, ''), at.title) AS title, " +
                            "ao.deadline_at, at.description_md, ao.status, " +
                            "sa.submission_status, sa.first_submit_at, sa.last_submit_at, " +
                            "sa.accepted_problem_count, sa.submitted_problem_count, sa.problem_count, " +
                            "sa.best_total_score, sa.latest_total_score, sp.student_no, sp.real_name, sp.id, " +
                            "ao.pta_problem_set_id " +
                            "FROM student_profile sp " +
                            "JOIN class_member cm ON cm.student_id = sp.id AND cm.member_status = 'ACTIVE' " +
                            "JOIN teaching_class tc ON tc.id = cm.class_id " +
                            "JOIN assignment_offering ao ON ao.class_id = cm.class_id " +
                            "JOIN assignment_template at ON at.id = ao.template_id " +
                            "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id AND sa.student_id = sp.id " +
                            "WHERE sp.student_no = ?1 " +
                            "AND (?2 IS NULL OR cm.class_id = ?2) " +
                            "AND (tc.status IS NULL OR tc.status = 'ACTIVE') " +
                            "AND ao.status <> 'ARCHIVED' " +
                            "AND (at.title LIKE '%推荐练习%' OR ao.title_override LIKE '%推荐练习%') " +
                            "ORDER BY tc.id, COALESCE(ao.seq_no, 999999), ao.id"
            ).setParameter(1, studentNo)
                    .setParameter(2, classId)
                    .getResultList();

            List<Map<String, Object>> practices = new ArrayList<>();
            for (Object[] row : rows) {
                Long offeringId = toLong(row[0]);
                String submissionStatus = toStringValue(row[9]);
                int submittedProblemCount = toInt(row[13]);
                Double score = firstNonNull(toDouble(row[16]), toDouble(row[15]), 0.0);

                Map<String, Object> practice = new LinkedHashMap<>();
                practice.put("type", "pta_practice");
                practice.put("id", offeringId);
                practice.put("offeringId", offeringId);
                practice.put("templateId", toLong(row[1]));
                practice.put("classId", toLong(row[2]));
                practice.put("className", row[3]);
                practice.put("classCode", row[4]);
                practice.put("name", row[5]);
                practice.put("deadline", formatDateTime(row[6]));
                practice.put("description", toStringValue(row[7]));
                practice.put("status", mapStudentAssignmentStatus(submissionStatus, submittedProblemCount, score));
                practice.put("submitTime", formatDateTime(row[11] != null ? row[11] : row[10]));
                practice.put("score", roundTwoDecimals(score));
                practice.put("acceptedProblemCount", toInt(row[12]));
                practice.put("submittedProblemCount", submittedProblemCount);
                practice.put("problemCount", toInt(row[14]));
                practice.put("source", "pta_practice");

                practices.add(practice);
            }

            response.put("success", true);
            response.put("data", practices);
            response.put("source", "pta_practice");
            response.put("studentNo", studentNo);
            System.out.println("PTA推荐练习查询: 学号=" + studentNo + ", 数量=" + practices.size());
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", true);
            response.put("data", new ArrayList<>());
            response.put("source", "degraded_empty");
            response.put("message", "获取推荐练习失败: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取当前登录学生的 PTA 推荐题目集（标题含"推荐题目集"）
     */
    @GetMapping("/api/student/current/pta-practice-sets")
    public ResponseEntity<Map<String, Object>> getCurrentStudentPtaPracticeSets(
            HttpServletRequest request,
            @RequestParam(required = false) Long classId) {
        String studentNo = studentSessionResolver.requireStudentId(request);
        return getPtaPracticeSetsByStudentNo(studentNo, classId);
    }

    /**
     * 获取指定学生的 PTA 推荐题目集（标题含"推荐题目集"）
     */
    @GetMapping("/api/student/{studentId}/pta-practice-sets")
    public ResponseEntity<Map<String, Object>> getPtaPracticeSetsByStudent(
            @PathVariable String studentId,
            HttpServletRequest request,
            @RequestParam(required = false) Long classId) {
        String authorizedStudentNo = studentSessionResolver.requireAuthorizedStudentId(studentId, request);
        return getPtaPracticeSetsByStudentNo(authorizedStudentNo, classId);
    }

    private ResponseEntity<Map<String, Object>> getPtaPracticeSetsByStudentNo(String studentNo, Long classId) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT ao.id, ao.template_id, ao.class_id, tc.name, tc.class_code, " +
                            "COALESCE(NULLIF(ao.title_override, ''), at.title) AS title, " +
                            "ao.deadline_at, at.description_md, ao.status, " +
                            "sa.submission_status, sa.first_submit_at, sa.last_submit_at, " +
                            "sa.accepted_problem_count, sa.submitted_problem_count, sa.problem_count, " +
                            "sa.best_total_score, sa.latest_total_score, sp.student_no, sp.real_name, sp.id " +
                            "FROM student_profile sp " +
                            "JOIN class_member cm ON cm.student_id = sp.id AND cm.member_status = 'ACTIVE' " +
                            "JOIN teaching_class tc ON tc.id = cm.class_id " +
                            "JOIN assignment_offering ao ON ao.class_id = cm.class_id " +
                            "JOIN assignment_template at ON at.id = ao.template_id " +
                            "LEFT JOIN student_assignment sa ON sa.offering_id = ao.id AND sa.student_id = sp.id " +
                            "WHERE sp.student_no = ?1 " +
                            "AND (?2 IS NULL OR cm.class_id = ?2) " +
                            "AND (tc.status IS NULL OR tc.status = 'ACTIVE') " +
                            "AND ao.status <> 'ARCHIVED' " +
                            "AND (at.title LIKE '%推荐题目集%' OR ao.title_override LIKE '%推荐题目集%') " +
                            "ORDER BY tc.id, COALESCE(ao.seq_no, 999999), ao.id"
            ).setParameter(1, studentNo)
                    .setParameter(2, classId)
                    .getResultList();

            List<Map<String, Object>> practices = new ArrayList<>();
            for (Object[] row : rows) {
                Long offeringId = toLong(row[0]);
                String submissionStatus = toStringValue(row[9]);
                int submittedProblemCount = toInt(row[13]);
                Double score = firstNonNull(toDouble(row[16]), toDouble(row[15]), 0.0);

                Map<String, Object> practice = new LinkedHashMap<>();
                practice.put("type", "pta_practice_set");
                practice.put("id", offeringId);
                practice.put("offeringId", offeringId);
                practice.put("templateId", toLong(row[1]));
                practice.put("classId", toLong(row[2]));
                practice.put("className", row[3]);
                practice.put("classCode", row[4]);
                practice.put("name", row[5]);
                practice.put("title", row[5]);
                practice.put("deadline", formatDateTime(row[6]));
                practice.put("description", toStringValue(row[7]));
                practice.put("status", row[8]);
                practice.put("submissionStatus", submissionStatus);
                practice.put("acceptedProblemCount", toInt(row[12]));
                practice.put("submittedProblemCount", submittedProblemCount);
                practice.put("problemCount", toInt(row[14]));
                practice.put("score", roundTwoDecimals(score));
                practice.put("studentProfileId", toLong(row[19]));
                String ptaProblemSetId = toStringValue(row[20]);
                practice.put("sourceUrl", ptaProblemSetId == null || ptaProblemSetId.isBlank()
                        ? null
                        : ptaProblemSetBaseUrl.replaceAll("/+$", "") + "/" + ptaProblemSetId);
                practice.put("sourceLabel", "PTA");
                practices.add(practice);
            }

            response.put("success", true);
            response.put("data", practices);
            response.put("source", "pta_practice_sets");
        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("data", new ArrayList<>());
            response.put("message", "获取PTA推荐题目集失败: " + e.getMessage());
        }
        return ResponseEntity.ok(response);
    }

    private int warmupLeetCodeDataIfNeeded() {
        if (leetCodeDataWarmupAttempted) {
            return 0;
        }

        synchronized (this) {
            if (leetCodeDataWarmupAttempted) {
                return 0;
            }
            leetCodeDataWarmupAttempted = true;
            try {
                int synced = leetCodeSyncService.syncProblemsFromJson(LEETCODE_CLEANED_DATA_PATH);
                System.out.println("LeetCode 数据预热完成，导入数量: " + synced);
                return synced;
            } catch (Exception e) {
                System.out.println("LeetCode 数据预热失败: " + e.getMessage());
                return 0;
            }
        }
    }

    /**
     * 获取当前登录用户的所有推荐练习
     * @param request HTTP请求
     * @return 响应实体，包含所有推荐练习列表
     */
    @GetMapping("/api/current/recommendedPractices")
    public ResponseEntity<Map<String, Object>> getCurrentUserRecommendedPractices(
            HttpServletRequest request,
            @RequestParam(required = false) Long classId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 从Session中获取当前用户名
            if (request != null) {
                String studentNo = studentSessionResolver.requireStudentId(request);
                return getAllRecommendedPracticesByStudent(studentNo, classId);
            }
            HttpSession session = request.getSession(false);
            String currentUsername;
            if (session != null) {
                currentUsername = (String) session.getAttribute("username");
            } else {
                currentUsername = null;
            }

            // 如果用户未登录，返回错误信息
            if (currentUsername == null) {
                response.put("success", false);
                response.put("message", "用户未登录或会话已过期");
                return ResponseEntity.ok(response);
            }

            // 获取学生ID
            StudentController studentController = applicationContext.getBean(StudentController.class);
            ResponseEntity<Map<String, Object>> studentIdResponse = studentController.findStudentIdByUsername(currentUsername);
            Map<String, Object> studentIdData = studentIdResponse.getBody();

            Integer numericId = null;
            if (studentIdData != null && (Boolean) studentIdData.getOrDefault("success", false)) {
                numericId = (Integer) studentIdData.get("studentId");
                System.out.println("获取到学生ID: " + numericId);

                // 通过数字ID查学号，再调用推荐练习方法
                try {
                    String sno = (String) em.createNativeQuery(
                            "SELECT student_no FROM student_profile WHERE id = ?1"
                    ).setParameter(1, numericId).getSingleResult();
                    return getAllRecommendedPracticesByStudent(sno, classId);
                } catch (Exception ex) {
                    System.out.println("查询学号失败，尝试用ID直接查询: " + ex.getMessage());
                    response.put("success", true);
                    response.put("data", new ArrayList<>());
                    response.put("message", "无法定位学生学号");
                    return ResponseEntity.ok(response);
                }
            } else {
                response.put("success", false);
                response.put("message", "未找到学生信息");
                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", true);
            response.put("data", new ArrayList<>());
            response.put("message", "获取推荐练习失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 获取特定学生特定实验的推荐练习
     * @param studentId 学生ID
     * @param experimentId 实验ID
     * @return 响应实体，包含推荐练习列表
     */
    @GetMapping("/api/student/{studentId}/experiment/{experimentId}/recommendedPractices")
    public ResponseEntity<Map<String, Object>> getRecommendedPracticesForExperiment(
            @PathVariable int studentId,
            @PathVariable int experimentId,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            if (request != null) {
                String authorizedStudentId = studentSessionResolver.requireAuthorizedStudentId(String.valueOf(studentId), request);
                studentId = Integer.parseInt(authorizedStudentId);
            }
            List<Map<String, Object>> recommendedPractices = getRecommendedPracticesByExperiment(studentId, experimentId);
            
            // 对返回的数据进行处理，确保前端能正确显示
            for (Map<String, Object> practice : recommendedPractices) {
                if (practice.containsKey("type") && "problem".equals(practice.get("type"))) {
                    System.out.println("返回题目: " + practice.get("number") + ". " + practice.get("title"));
                    System.out.println("URL链接: " + practice.get("url"));
                }
            }

            response.put("success", true);
            response.put("data", recommendedPractices);
            response.put("studentId", studentId);
            response.put("experimentId", experimentId);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", true);
            response.put("data", new ArrayList<>());
            response.put("studentId", studentId);
            response.put("experimentId", experimentId);
            response.put("message", "获取推荐练习失败: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }


    /**
     * 学生查看自己某实验的所有提交尝试（用于 AI 错误分析）
     * GET /api/submissions?experimentId=7
     */
    @GetMapping("/api/submissions")
    public ResponseEntity<Map<String, Object>> getMySubmissions(
            @RequestParam(value = "experimentId", required = false) Integer experimentId,
            HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            String studentNo = studentSessionResolver.requireStudentId(request);
            List<Map<String, Object>> submissions = new ArrayList<>();

            if (experimentId != null) {
                List<com.tap.backend.academic.entity.StudentSubmissionAttempt> attempts =
                        teacherExperimentQueryDao.findSubmissionAttemptsForErrorAnalysis(studentNo, experimentId);

                if (attempts != null) {
                    SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    for (int i = 0; i < attempts.size(); i++) {
                        com.tap.backend.academic.entity.StudentSubmissionAttempt a = attempts.get(i);
                        Map<String, Object> sub = new LinkedHashMap<>();
                        sub.put("attemptNo", i + 1);
                        sub.put("judgeStatus", a.getJudgeStatus() != null ? a.getJudgeStatus() : "UNKNOWN");
                        sub.put("compiler", a.getCompiler() != null ? a.getCompiler() : "");
                        sub.put("errorMessage", a.getErrorMessage() != null ? a.getErrorMessage() : "");
                        sub.put("code", a.getCode() != null ? a.getCode() : "");
                        sub.put("problemTitle", a.getProblemTitle() != null ? a.getProblemTitle() : "");
                        if (a.getSubmittedAt() != null) {
                            sub.put("submittedAt", isoFormat.format(a.getSubmittedAt()));
                        }
                        sub.put("score", a.getScore());
                        submissions.add(sub);
                    }
                }
            }

            response.put("success", true);
            response.put("data", submissions);
            response.put("source", "student_problem_attempt");
            response.put("studentNo", studentNo);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    //根据实验提交id查询学生实验提交详情数据
    /**
     * 获取学生实验提交详情
     * @param submissionId 提交ID，格式为"学号-实验ID"，例如：2019443672-1
     * @return 包含提交详情的响应
     */
    @GetMapping("/api/submissions/{submissionId}")
    public ResponseEntity<Map<String, Object>> getSubmissionDetail(
            @PathVariable String submissionId,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 解析submissionId (格式: studentId-experimentId)
            legacySessionAccessResolver.requireTeacherOrAdmin(request);
            int separatorIndex = submissionId.lastIndexOf('-');
            if (separatorIndex <= 0 || separatorIndex >= submissionId.length() - 1) {
                response.put("success", false);
                response.put("message", "提交ID格式不正确，应为'学号-实验ID'");
                return ResponseEntity.badRequest().body(response);
            }

            String studentIdKey = submissionId.substring(0, separatorIndex).trim();
            Integer experimentId = Integer.parseInt(submissionId.substring(separatorIndex + 1).trim());
            Integer studentId = tryParseInteger(studentIdKey);

            System.out.println("提交id信息"+studentId+"---"+experimentId);

            // 获取学生信息
            TeacherStudentAssignmentRow assignment;
            if (teacherSessionResolver.isCurrentAdmin(request)) {
                assignment = teacherExperimentQueryDao.findStudentAssignmentDetailBySubmissionKey(
                        studentIdKey,
                        experimentId
                );
            } else {
                com.tap.backend.academic.entity.teacher.Teacher currentTeacher =
                        teacherSessionResolver.requireCurrentTeacherOrAdminNull(request);
                assignment = currentTeacher == null
                        ? null
                        : teacherExperimentQueryDao.findStudentAssignmentDetailForTeacher(
                                currentTeacher.getTeacher_id(),
                                studentIdKey,
                                experimentId
                        );
            }
            if (assignment == null) {
                response.put("success", false);
                response.put("message", "未找到对应的学生作业信息");
                return ResponseEntity.ok(response);
            }

            String username = assignment.getStudentUsername();

            // 获取实验信息
            Experiment experiment = experimentService.findExperimentById(experimentId);
            if (experiment == null) {
                experiment = new Experiment();
                experiment.setExperiment_id(experimentId);
                experiment.setName(assignment.getExperimentName());
            }

            // 获取提交记录
            if (request != null) {
                List<TeacherSubmissionProblemRow> resolvedProblemRows = Collections.emptyList();
                String resolvedCode = "";
                Double resolvedScore;
                Date resolvedSubmitTime;
                String resolvedReport;
                String resolvedStatus;
                AIRemarks resolvedAiRemarks;

                if (unifiedSubmissionDetailEnabled) {
                    resolvedProblemRows = teacherExperimentQueryDao.findSubmissionProblemRows(
                            assignment.getStudentId(),
                            experimentId
                    );
                    resolvedCode = buildUnifiedSubmissionCode(resolvedProblemRows);
                    if (submissionDetailLegacyCodeFallbackEnabled) {
                        String legacyCode = resolveLegacySubmissionCode(username, studentIdKey, studentId, experimentId);
                        if (isMoreCompleteCode(legacyCode, resolvedCode)) {
                            resolvedCode = legacyCode;
                        }
                    }
                    resolvedScore = assignment.getScore();
                    resolvedSubmitTime = assignment.getSubmitTime();
                    resolvedReport = resolveLegacySubmissionReport(username, studentIdKey, experiment, experimentId);
                    resolvedAiRemarks = resolveLegacyAiRemarks(studentId, experimentId);

                    if ((resolvedProblemRows == null || resolvedProblemRows.isEmpty())
                            && (resolvedCode == null || resolvedCode.isBlank())
                            && ("NOT_STARTED".equalsIgnoreCase(assignment.getSubmissionStatus())
                            || assignment.getSubmissionStatus() == null)) {
                        response.put("success", false);
                        response.put("message", "No submission data found for this student and experiment");
                        return ResponseEntity.ok(response);
                    }

                    resolvedStatus = mapSubmissionDetailStatus(
                            assignment.getSubmissionStatus(),
                            resolvedScore,
                            resolvedCode
                    );
                } else {
                    Score legacyScore = resolveLegacyScore(username, studentIdKey, experiment);
                    resolvedCode = resolveLegacySubmissionCode(username, studentIdKey, studentId, experimentId);
                    resolvedScore = legacyScore == null || legacyScore.getScore() == null
                            ? assignment.getScore()
                            : legacyScore.getScore().doubleValue();
                    resolvedSubmitTime = legacyScore != null && legacyScore.getSubmit_time() != null
                            ? legacyScore.getSubmit_time()
                            : assignment.getSubmitTime();
                    resolvedReport = resolveLegacySubmissionReport(username, studentIdKey, experiment, experimentId);
                    resolvedAiRemarks = resolveLegacyAiRemarks(studentId, experimentId);

                    if ((resolvedCode == null || resolvedCode.isBlank())
                            && legacyScore == null
                            && ("NOT_STARTED".equalsIgnoreCase(assignment.getSubmissionStatus())
                            || assignment.getSubmissionStatus() == null)) {
                        response.put("success", false);
                        response.put("message", "No submission data found for this student and experiment");
                        return ResponseEntity.ok(response);
                    }

                    resolvedStatus = legacyScore != null && "completed".equalsIgnoreCase(legacyScore.getStatus())
                            ? "graded"
                            : mapSubmissionDetailStatus(assignment.getSubmissionStatus(), resolvedScore, resolvedCode);
                }

                response.put("studentId", assignment.getStudentId());
                response.put("studentName", assignment.getStudentName());
                response.put("experimentId", experimentId);
                response.put("experimentName", assignment.getExperimentName());
                response.put("submitTime", resolvedSubmitTime);
                response.put("class", assignment.getClassName());
                response.put("code", resolvedCode != null ? resolvedCode : "");
                // 结构化题目数据（题号/标题/题面/代码），供前端按题展示题目信息
                response.put("problems", buildSubmissionProblems(resolvedProblemRows));
                response.put("date", resolvedSubmitTime);
                response.put("report", resolvedReport);
                response.put("teacherComment", extractTeacherComment(resolvedReport));
                response.put(
                        "plagiarismRate",
                        roundTwoDecimals(calculateAveragePlagiarismRate(assignment.getPlagiarismRate()))
                );
                response.put("score", resolvedScore);
                response.put("status", resolvedStatus);
                response.put("aiRemarks", resolvedAiRemarks == null ? null : resolvedAiRemarks.getAiremark());
                response.put("success", true);
                return ResponseEntity.ok(response);
            }

            List<TeacherSubmissionProblemRow> problemRows = teacherExperimentQueryDao.findSubmissionProblemRows(
                    assignment.getStudentId(),
                    experimentId
            );
            SubmissionDetailEntity submission = submissionDao.findDetailByUsernameAndExperimentId(username, experimentId);
            Submission latestSubmission = resolveLatestSubmission(username, assignment.getStudentId(), experimentId);
            StudentCode studentCode = studentId == null
                    ? null
                    : studentCodeService.findCodeByStudentIdAndExperimentId(studentId, experimentId);
            Score score = studentId == null
                    ? null
                    : scoreService.findByUsernameAndExperimentNum(String.valueOf(studentId), experiment.getNum());
            if (score == null && username != null && !username.isBlank()) {
                score = scoreService.findByUsernameAndExperimentNum(username, experiment.getNum());
            }

            if (submission == null
                    && latestSubmission == null
                    && studentCode == null
                    && score == null
                    && (problemRows == null || problemRows.isEmpty())
                    && ("NOT_STARTED".equalsIgnoreCase(assignment.getSubmissionStatus())
                    || assignment.getSubmissionStatus() == null)) {
                response.put("success", false);
                response.put("message", "No submission data found for this student and experiment");
                return ResponseEntity.ok(response);
            }

            AIRemarks aiRemarks = aiRemarksService.getAIRemarkByStudentAndExperiment(studentIdKey, experimentId);



            // 构建响应数据
            String mergedCode = buildUnifiedSubmissionCode(problemRows);
            if ((mergedCode == null || mergedCode.isBlank()) && latestSubmission != null) {
                mergedCode = latestSubmission.getCode();
            }
            if (studentCode != null && isMoreCompleteCode(studentCode.getCode(), mergedCode)) {
                mergedCode = studentCode.getCode();
            }
            Double mergedScore = assignment.getScore();
            Date mergedSubmitTime = assignment.getSubmitTime();
            String mergedReport = latestSubmission != null && latestSubmission.getReport() != null
                    ? latestSubmission.getReport()
                    : null;

            response.put("studentId", assignment.getStudentId());
            response.put("studentName", assignment.getStudentName());
            response.put("experimentId", experimentId);
            response.put("experimentName", assignment.getExperimentName());
            response.put("submitTime", mergedSubmitTime);
            response.put("class", assignment.getClassName());
            response.put("code", mergedCode != null ? mergedCode : "");
            // 结构化题目数据（题号/标题/题面/代码），供前端按题展示题目信息
            response.put("problems", buildSubmissionProblems(problemRows));
            response.put("date", mergedSubmitTime);
//            Map<String, Object> submissionData = new HashMap<>();
//            submissionData.put("submissionId", submissionId);
//            submissionData.put("studentId", studentId);
//            submissionData.put("studentName", student.getName());
//            submissionData.put("studentUsername", username);
//            submissionData.put("className", student.getClass_name());
//            submissionData.put("experimentId", experimentId);
//            submissionData.put("experimentName", experiment.getName());
//            submissionData.put("deadline", experiment.getDeadline());

            response.put("report", mergedReport);
            response.put("teacherComment", extractTeacherComment(mergedReport));
            double avgPlagiarismRate = roundTwoDecimals(calculateAveragePlagiarismRate(assignment.getPlagiarismRate()));
            response.put("plagiarismRate", avgPlagiarismRate);
            response.put("score", mergedScore);
            response.put("status", mapSubmissionDetailStatus(assignment.getSubmissionStatus(), mergedScore, mergedCode));
//            submissionData.put("code", submission.getCode());
//            submissionData.put("report", submission.getReport());
//            submissionData.put("submitTime", submission.getSubmit_time());

            // 添加成绩信息
//            if (score != null) {
//                submissionData.put("score", score.getScore());
//                submissionData.put("status", score.getStatus());
//                submissionData.put("plagiarismRate", score.getPlagiarism_rate());
//            } else {
//                submissionData.put("score", 0);
//                submissionData.put("status", "not_scored");
//                submissionData.put("plagiarismRate", 0.0);
//            }

            // 添加AI点评
            if (aiRemarks != null) {
                response.put("aiRemarks", aiRemarks.getAiremark());
            } else {
                response.put("aiRemarks", null);
            }

            response.put("success", true);

            return ResponseEntity.ok(response);

        } catch (NumberFormatException e) {
            response.put("success", false);
            response.put("message", "提交ID格式错误: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取提交详情失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/api/submissions/{submissionId}/learning-tracking")
    public ResponseEntity<Map<String, Object>> getLearningTracking(
            @PathVariable String submissionId, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            legacySessionAccessResolver.requireTeacherOrAdmin(request);
            int separatorIndex = submissionId.lastIndexOf('-');
            if (separatorIndex <= 0 || separatorIndex >= submissionId.length() - 1) {
                response.put("success", false);
                response.put("message", "提交ID格式不正确，应为'学号-实验ID'");
                return ResponseEntity.badRequest().body(response);
            }
            String studentNo = submissionId.substring(0, separatorIndex).trim();
            int experimentId = Integer.parseInt(submissionId.substring(separatorIndex + 1).trim());
            Integer studentProfileId = null;
            try {
                Object idObj = em.createNativeQuery(
                        "SELECT id FROM student_profile WHERE student_no = ?1 LIMIT 1"
                ).setParameter(1, studentNo).getSingleResult();
                if (idObj instanceof Number) studentProfileId = ((Number) idObj).intValue();
            } catch (Exception ignored) {}
            LearningTrackingResponse data = learningTrackingService.getLearningTracking(
                    studentNo, experimentId, studentProfileId);
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(403).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "获取学情追踪失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 单独评分：对单个学生提交进行评分
     */
    @PostMapping("/api/submissions/{submissionId}/grade")
    public ResponseEntity<Map<String, Object>> gradeSingle(
            @PathVariable String submissionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest servletRequest) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            legacySessionAccessResolver.requireTeacherOrAdmin(servletRequest);

            int separatorIndex = submissionId.lastIndexOf('-');
            if (separatorIndex <= 0 || separatorIndex >= submissionId.length() - 1) {
                response.put("success", false);
                response.put("message", "提交ID格式不正确，应为'学号-实验ID'");
                return ResponseEntity.badRequest().body(response);
            }

            String studentIdKey = submissionId.substring(0, separatorIndex).trim();
            int experimentId = Integer.parseInt(submissionId.substring(separatorIndex + 1).trim());

            // 查找实验，获取实验编号
            Experiment experiment = experimentService.findExperimentById(experimentId);
            int experimentNum = experiment != null ? experiment.getNum() : experimentId;

            // 查找已有成绩
            Score existingScore = scoreService.findByUsernameAndExperimentNum(studentIdKey, experimentNum);
            Score score = existingScore != null ? existingScore : new Score();
            score.setUsername(studentIdKey);
            score.setExperiment_id(experimentId);
            score.setNum(experimentNum);

            // 从请求体获取评分数据
            Object scoreValue = body.get("score");
            if (scoreValue instanceof Number) {
                score.setScore(((Number) scoreValue).intValue());
            }
            Object plagiarismValue = body.get("plagiarismRate");
            if (plagiarismValue instanceof Number) {
                score.setPlagiarism_rate(String.valueOf(plagiarismValue));
            }
            score.setStatus("graded");
            score.setSubmit_time(new java.util.Date());

            boolean saved;
            if (existingScore != null) {
                saved = scoreService.updateScore(score);
            } else {
                saved = scoreService.saveScore(score);
            }

            // 保存AI评语
            Object aiComment = body.get("aiComment");
            if (aiComment instanceof String && !((String) aiComment).isBlank()) {
                String experimentName = experiment != null ? experiment.getName() : "实验" + experimentId;
                AIRemarks remarks = new AIRemarks(studentIdKey, studentIdKey, experimentId,
                        experimentName, (String) aiComment);
                aiRemarksService.saveOrUpdateAIRemark(remarks);
            }

            if (!saved) {
                response.put("success", false);
                response.put("message", "保存成绩失败，数据库写入异常");
                return ResponseEntity.status(500).body(response);
            }

            response.put("success", true);
            response.put("message", "评分成功");
            return ResponseEntity.ok(response);
        } catch (NumberFormatException e) {
            response.put("success", false);
            response.put("message", "实验ID解析失败");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "评分失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 批量评分：对多个学生提交进行统一评分
     */
    @PostMapping("/api/submissions/batch-grade")
    public ResponseEntity<Map<String, Object>> batchGrade(
            @RequestBody BatchGradeRequest request,
            HttpServletRequest servletRequest) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            legacySessionAccessResolver.requireTeacherOrAdmin(servletRequest);

            if (request == null || request.submissionIds() == null || request.submissionIds().isEmpty()) {
                response.put("success", false);
                response.put("message", "提交ID列表不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            int processed = 0;
            int failed = 0;
            List<String> errors = new ArrayList<>();

            for (String submissionId : request.submissionIds()) {
                try {
                    int separatorIndex = submissionId.lastIndexOf('-');
                    if (separatorIndex <= 0 || separatorIndex >= submissionId.length() - 1) {
                        failed++;
                        errors.add("提交ID格式不正确: " + submissionId);
                        continue;
                    }

                    String studentIdKey = submissionId.substring(0, separatorIndex).trim();
                    int experimentId = Integer.parseInt(submissionId.substring(separatorIndex + 1).trim());

                    // 查找实验，获取实验编号
                    Experiment experiment = experimentService.findExperimentById(experimentId);
                    int experimentNum = experiment != null ? experiment.getNum() : experimentId;

                    // 查找已有成绩
                    Score existingScore = scoreService.findByUsernameAndExperimentNum(studentIdKey, experimentNum);

                    Score score = existingScore != null ? existingScore : new Score();
                    score.setUsername(studentIdKey);
                    score.setExperiment_id(experimentId);
                    score.setNum(experimentNum);
                    if (request.score() != null) {
                        score.setScore(request.score().intValue());
                    }
                    if (request.plagiarismRate() != null) {
                        score.setPlagiarism_rate(String.valueOf(request.plagiarismRate()));
                    }
                    score.setStatus("graded");
                    score.setSubmit_time(new java.util.Date());

                    boolean saved;
                    if (existingScore != null) {
                        saved = scoreService.updateScore(score);
                    } else {
                        saved = scoreService.saveScore(score);
                    }

                    if (!saved) {
                        failed++;
                        errors.add(submissionId + ": 保存成绩失败");
                        continue;
                    }

                    // 保存AI评语
                    if (request.aiComment() != null && !request.aiComment().isBlank()) {
                        String experimentName = experiment != null ? experiment.getName() : "实验" + experimentId;
                        AIRemarks remarks = new AIRemarks(studentIdKey, studentIdKey, experimentId,
                                experimentName, request.aiComment());
                        aiRemarksService.saveOrUpdateAIRemark(remarks);
                    }

                    processed++;
                } catch (NumberFormatException e) {
                    failed++;
                    errors.add("实验ID解析失败: " + submissionId);
                } catch (Exception e) {
                    failed++;
                    errors.add(submissionId + ": " + e.getMessage());
                }
            }

            response.put("success", true);
            response.put("processed", processed);
            response.put("failed", failed);
            if (!errors.isEmpty()) {
                response.put("errors", errors);
            }
            response.put("message", String.format("批量评分完成，成功处理 %d 条" + (failed > 0 ? "，%d 条失败" : ""),
                    processed, failed > 0 ? failed : null).replace("null", ""));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "批量评分失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    public record BatchGradeRequest(
            List<String> submissionIds,
            Double score,
            Double plagiarismRate,
            String aiComment
    ) {}

    @GetMapping("/api/student/learning-analysis")
    public ResponseEntity<?> getLearningAnalysis(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            String username = studentSessionResolver.requireStudentId(request);
            if (username == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "用户未登录"));
            }
            // 通过username查找studentId
            StudentController studentController = applicationContext.getBean(StudentController.class);
            ResponseEntity<Map<String, Object>> sidResp = ResponseEntity.ok(Map.of("success", true, "studentId", username));
            Map<String, Object> sidData = sidResp.getBody();
            String studentId = null;
            if (sidData != null && Boolean.TRUE.equals(sidData.get("success"))) {
                Object sid = sidData.get("studentId");
                studentId = sid != null ? String.valueOf(sid) : null;
            }
            if (studentId == null) {
                return ResponseEntity.ok(Map.of("success", false, "message", "未找到学生信息"));
            }
            Map<String, Object> profile = profileService.getStudentProfile(studentId);
            if (profile.containsKey("error")) {
                return ResponseEntity.ok(Map.of("success", false, "message", profile.get("error")));
            }
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "获取学习分析失败: " + e.getMessage()));
        }
    }

    /**
     * 按需生成/刷新单个实验的AI点评
     * 逻辑：如果 force=false 且DB已有缓存，直接返回；否则调用DeepSeek生成并存入DB
     */
    @PostMapping("/api/experiments/{experimentId}/ai-comment/generate")
    public ResponseEntity<Map<String, Object>> generateAiComment(
            @PathVariable int experimentId,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) String studentId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();
        try {
            HttpSession session = request.getSession(false);
            String currentUsername;
            if (studentId != null && !studentId.isBlank()) {
                currentUsername = studentId;
            } else {
                currentUsername = studentSessionResolver.requireStudentId(request);
            }
            if (currentUsername == null) {
                response.put("success", false);
                response.put("message", "用户未登录");
                return ResponseEntity.ok(response);
            }

            // 如果不是强制刷新，先查DB缓存（新旧学生通用，学号字符串做key）
            if (!force) {
                AIRemarks cached = aiRemarksService.getAIRemarkByStudentAndExperiment(currentUsername, experimentId);
                if (cached != null && cached.getAiremark() != null && !cached.getAiremark().isBlank()) {
                    response.put("success", true);
                    response.put("aiComment", cached.getAiremark());
                    response.put("source", "cache");
                    return ResponseEntity.ok(response);
                }
            }

            // 获取代码：优先从请求体，否则从DB查
            String code = null;
            if (body != null && body.get("code") != null) {
                code = body.get("code").toString();
            }
            String expName = "实验" + experimentId;
            StringBuilder expContent = new StringBuilder();
            String studentName = currentUsername;
            try {
                Experiment exp = experimentService.findExperimentById(experimentId);
                if (exp != null) {
                    if (exp.getName() != null) expName = exp.getName();
                    if (exp.getDescribe() != null) expContent.append("实验描述：").append(exp.getDescribe()).append("\n");
                }
                if (code == null || code.isBlank()) {
                    try {
                        int sid = Integer.parseInt(currentUsername);
                        StudentCode sc = studentCodeService.findCodeByStudentIdAndExperimentId(sid, experimentId);
                        if (sc != null && sc.getCode() != null && !sc.getCode().isBlank()) {
                            code = sc.getCode();
                        }
                    } catch (NumberFormatException ignored) {}
                }
            } catch (Exception e) {
                System.out.println("[AI点评] 获取实验信息异常: " + e.getMessage());
            }

            // 构建分析内容：优先用代码，否则用实验信息
            String analysisContent;
            if (code != null && !code.isBlank()) {
                analysisContent = "学生代码：\n```c\n" + code + "\n```";
            } else {
                analysisContent = expContent.length() > 0 ? expContent.toString() : "实验名称：" + expName;
            }

            // 调用DeepSeek生成AI点评
            String aiComment = callDeepSeekForCodeReview(analysisContent, expName, studentName);
            if (aiComment == null || aiComment.isBlank()) {
                response.put("success", false);
                response.put("message", "AI点评生成失败，请稍后重试");
                return ResponseEntity.ok(response);
            }

            // 保存到DB（新旧学生通用，学号字符串做主键）
            AIRemarks remarks = new AIRemarks(currentUsername, studentName, experimentId, expName, aiComment);
            aiRemarksService.saveOrUpdateAIRemark(remarks);

            response.put("success", true);
            response.put("aiComment", aiComment);
            response.put("source", "deepseek");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("success", false);
            response.put("message", "生成AI点评失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 调用DeepSeek为单个实验的代码生成AI点评（Markdown格式）
     */
    private String callDeepSeekForCodeReview(String code, String experimentName, String studentName) throws Exception {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            return null;
        }

        // 截断过长的代码（保留前6000字符）
        if (code.length() > 6000) {
            code = code.substring(0, 6000) + "\n... (代码过长，已截断)";
        }

        String systemPrompt = "分析学生代码，用纯文本（不用Markdown）输出三条，总共不超过250字：\n"
                + "1. 代码问题：该学生在本次实验中的具体代码问题\n"
                + "2. 薄弱点：该同学的知识薄弱点，依据代码说明\n"
                + "3. 教学建议：针对性的教学建议（如抓某某知识点）\n";

        String userPrompt = "学生: " + studentName + "\n"
                + "实验: " + experimentName + "\n\n"
                + (code != null && code.contains("```c") ? code
                   : "实验内容:\n" + code.substring(0, Math.min(code.length(), 3000)));

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", deepseekModel);
        reqBody.addProperty("stream", false);
        reqBody.addProperty("max_tokens", 400);
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
                gsonInstance.toJson(reqBody),
                okhttp3.MediaType.parse("application/json; charset=utf-8"));

        Request httpReq = new Request.Builder()
                .url(deepseekBaseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + deepseekApiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response httpResp = aiHttpClient.newCall(httpReq).execute()) {
            if (!httpResp.isSuccessful() || httpResp.body() == null) {
                String errorBody = httpResp.body() != null ? httpResp.body().string() : "(no body)";
                System.err.println("[ApiController] DeepSeek请求失败 HTTP " + httpResp.code() + " body=" + errorBody);
                return null;
            }
            String respStr = httpResp.body().string();
            JsonObject respJson = JsonParser.parseString(respStr).getAsJsonObject();
            JsonArray choices = respJson.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                return choices.get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString().trim();
            }
        }
        return null;
    }

    private int nextExperimentNum() {
        List<Experiment> experiments = experimentService.findAllExperiments();
        if (experiments == null || experiments.isEmpty()) {
            return 1;
        }
        return experiments.stream()
                .mapToInt(Experiment::getNum)
                .max()
                .orElse(0) + 1;
    }

    private String joinRequirements(List<String> requirements) {
        if (requirements == null) {
            return "";
        }
        return requirements.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class ExperimentCreateRequest {
        private String name;
        private String deadline;
        private String description;
        private List<String> requirements;
        private List<Object> classes;
        private String status;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDeadline() {
            return deadline;
        }

        public void setDeadline(String deadline) {
            this.deadline = deadline;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getRequirements() {
            return requirements;
        }

        public void setRequirements(List<String> requirements) {
            this.requirements = requirements;
        }

        public List<Object> getClasses() {
            return classes;
        }

        public void setClasses(List<Object> classes) {
            this.classes = classes;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

}
