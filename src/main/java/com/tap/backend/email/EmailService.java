package com.tap.backend.email;

import com.tap.backend.academic.dao.UserDao;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.repo.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 邮件通知服务
 *
 * 负责预警邮件发送与 Redis 限频：
 * 同一学生同一实验 1 小时内仅发送一次。
 */
@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private static final String RATE_LIMIT_KEY_PREFIX = "tap:warning_email:";
    private static final long RATE_LIMIT_TTL_SECONDS = 3600;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserDao userDao;

    @Autowired
    private UserRepository userRepository;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${tap.frontend.base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    /**
     * 发送预警邮件（异步，不阻塞主流程）
     *
     * @param problems 触发预警的题目列表（至少一项）
     */
    @Async("aiExecutor")
    public void sendWarningEmail(String studentNo,
                                 String studentName,
                                 int experimentId,
                                 String experimentName,
                                 List<ProblemWarningInfo> problems) {

        if (problems == null || problems.isEmpty()) return;

        logger.info("EmailService.sendWarningEmail called: studentNo={}, experimentId={}, problems={}",
                studentNo, experimentId, problems.size());

        // 1. 限频检查
        if (!checkRateLimit(studentNo, experimentId)) {
            logger.info("RATE-LIMITED: student={}, experiment={}", studentNo, experimentId);
            return;
        }

        // 2. 获取学生信息（邮箱 + 姓名）
        StudentInfo info = resolveStudentInfo(studentNo, studentName);
        if (info.email == null || info.email.isBlank()) {
            logger.warn("SKIP: No email found for studentNo={}", studentNo);
            return;
        }

        // 3. 构建邮件
        String subject = String.format("[实验预警] 《%s》", experimentName);
        String body = buildMultiProblemEmailBody(info.displayName, experimentName, problems);

        // 4. 发送
        sendEmail(info.email, subject, body);
    }

    /**
     * 发送多实验汇总预警邮件（登录时自动扫描触发）。
     * 一封邮件包含一个学生在多个实验中触发预警的所有题目。
     */
    @Async("aiExecutor")
    public void sendMultiExperimentWarningEmail(String studentNo,
                                                 String studentName,
                                                 List<ExperimentWarningSummary> experiments) {
        logger.info("[邮件发送] 准备发送多实验汇总邮件 student={} 实验数={}", studentNo, experiments.size());
        if (experiments == null || experiments.isEmpty()) return;

        // 1. 获取学生信息（限频已由 scanActiveExperimentsAndWarn 入口处理）
        StudentInfo info = resolveStudentInfo(studentNo, studentName);
        if (info.email == null || info.email.isBlank()) {
            logger.warn("[邮件发送] ❌ 未找到邮箱 student={}", studentNo);
            return;
        }
        logger.info("[邮件发送] 学生邮箱={} 姓名={}", info.email, info.displayName);

        // 2. 构建邮件
        String subject = String.format("[学习预警] 您有%d个实验存在异常提交", experiments.size());
        String body = buildMultiExperimentEmailBody(info.displayName, experiments);
        logger.info("[邮件发送] 邮件标题={} 正文字数={}", subject, body.length());

        // 3. 发送
        logger.info("[邮件发送] 正在通过 SMTP 发送...");
        sendEmail(info.email, subject, body);
    }

    // ==================== 限频 ====================

    private boolean checkRateLimit(String studentNo, int experimentId) {
        if (redisTemplate == null) {
            return true; // 无 Redis 时放行
        }
        try {
            String key = RATE_LIMIT_KEY_PREFIX + studentNo + ":" + experimentId;
            Boolean set = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", RATE_LIMIT_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(set);
        } catch (Exception e) {
            logger.warn("Redis rate limit check failed: {}", e.getMessage());
            return true; // Redis 异常时放行，避免漏发
        }
    }

    // ==================== 邮箱查找 ====================

    private StudentInfo resolveStudentInfo(String studentNo, String fallbackName) {
        // 先尝试 tap_user.usernum（学号）
        try {
            UserEntity user = userDao.findTapUserByUsernum(studentNo);
            if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                String name = (user.getUsername() != null && !user.getUsername().isBlank())
                        ? user.getUsername() : fallbackName;
                return new StudentInfo(user.getEmail().trim(), name);
            }
        } catch (Exception ignored) {}

        // 再尝试 tap_user.username（JPA）
        try {
            com.tap.backend.domain.user.UserEntity domainUser =
                    userRepository.findByUsername(studentNo).orElse(null);
            if (domainUser != null && domainUser.getEmail() != null && !domainUser.getEmail().isBlank()) {
                String name = (domainUser.getDisplayName() != null && !domainUser.getDisplayName().isBlank())
                        ? domainUser.getDisplayName() : domainUser.getUsername();
                return new StudentInfo(domainUser.getEmail().trim(), name);
            }
        } catch (Exception ignored) {}

        // 最后尝试 legacy user 表
        try {
            UserEntity legacyUser = userDao.findByUsernameFromLegacyUserAnyRole(studentNo);
            if (legacyUser != null && legacyUser.getEmail() != null && !legacyUser.getEmail().isBlank()) {
                String name = (legacyUser.getUsername() != null && !legacyUser.getUsername().isBlank())
                        ? legacyUser.getUsername() : fallbackName;
                return new StudentInfo(legacyUser.getEmail().trim(), name);
            }
        } catch (Exception ignored) {}

        return new StudentInfo(null, fallbackName);
    }

    private static class StudentInfo {
        final String email;
        final String displayName;
        StudentInfo(String email, String displayName) {
            this.email = email;
            this.displayName = displayName;
        }
    }

    // ==================== 邮件发送 ====================

    private void sendEmail(String to, String subject, String htmlBody) {
        if (mailSender == null) {
            logger.warn("SKIP: JavaMailSender not configured (mailSender is null)");
            return;
        }
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true = HTML
            mailSender.send(mimeMessage);
            logger.info("SUCCESS: Email sent to={}, subject={}", to, subject);
        } catch (Exception e) {
            logger.error("FAILED to send email: {}", e.getMessage(), e);
        }
    }

    // ==================== 邮件内容构建 ====================

    private static final String EMAIL_CSS =
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;" +
            "font-size:14px;color:#333;line-height:1.8;margin:0;padding:0}" +
            ".wrap{max-width:640px;margin:0 auto;padding:24px}" +
            ".greeting{font-size:14px;margin:0 0 16px}" +
            ".summary{font-size:14px;margin:0 0 20px;color:#555}" +
            ".exp-block{margin:0 0 20px;padding:14px 18px;background:#f8f9fa;border-radius:10px;border-left:4px solid #1a73e8}" +
            ".exp-name{font-size:15px;font-weight:700;color:#1a73e8;margin:0 0 10px}" +
            ".prob-item{margin:6px 0 6px 14px;font-size:13px;color:#444}" +
            ".prob-title{font-weight:600;color:#202124}" +
            ".stat-summary{color:#666}" +
            ".status-badge{display:inline-block;padding:1px 8px;border-radius:10px;font-size:11px;font-weight:500;margin:0 2px}" +
            ".actions-block{margin:20px 0;padding:14px 18px;background:#e8f0fe;border-radius:10px}" +
            ".actions-title{font-size:13px;font-weight:600;color:#174ea6;margin:0 0 8px}" +
            ".action-item{font-size:13px;color:#333;margin:4px 0 4px 18px}" +
            ".footer{margin-top:24px;padding-top:16px;border-top:1px solid #e8eaed;font-size:12px;color:#999}" +
            ".footer a{color:#1a73e8;text-decoration:none;font-weight:500}" +
            ".signature{color:#999;margin:8px 0 0}";

    /**
     * 多实验汇总邮件正文（HTML）。
     */
    private String buildMultiExperimentEmailBody(String studentName,
                                                  List<ExperimentWarningSummary> experiments) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>").append(EMAIL_CSS).append("</style></head><body>");
        sb.append("<div class=\"wrap\">");
        sb.append("<p class=\"greeting\">").append(escapeHtml(studentName)).append(" 同学：</p>");
        sb.append("<p class=\"summary\">以下实验触发预警，请及时关注：</p>");

        for (ExperimentWarningSummary exp : experiments) {
            sb.append("<div class=\"exp-block\">");
            sb.append("<p class=\"exp-name\">▶ ").append(escapeHtml(exp.getExperimentName())).append("</p>");
            List<ProblemWarningInfo> problems = exp.getProblems();
            for (int i = 0; i < problems.size(); i++) {
                ProblemWarningInfo p = problems.get(i);
                sb.append("<p class=\"prob-item\">");
                sb.append("<span class=\"prob-title\">").append(i + 1).append(".「").append(escapeHtml(p.getProblemTitle())).append("」</span>");
                sb.append(" <span class=\"stat-summary\">：共提交 ").append(p.getTotalSubmissions()).append(" 次，通过 ").append(p.getAcceptedCount()).append(" 次</span>");
                appendStatusBadges(sb, p.getStatusCounts());
                sb.append("</p>");
            }
            sb.append("</div>");
        }

        // 建议行动
        List<ProblemWarningInfo> allProblems = collectAllProblems(experiments);
        List<String> allActions = collectDistinctActions(allProblems);
        if (!allActions.isEmpty()) {
            sb.append("<div class=\"actions-block\">");
            sb.append("<p class=\"actions-title\">💡 建议行动</p>");
            for (int i = 0; i < allActions.size(); i++) {
                sb.append("<p class=\"action-item\">").append(i + 1).append(". ").append(escapeHtml(allActions.get(i))).append("</p>");
            }
            sb.append("</div>");
        }

        sb.append("<div class=\"footer\">");
        sb.append("<p>提示：本邮件由系统自动发送，登录时每 2 小时最多提醒一次。</p>");
        sb.append("<p>请根据上述错误类型有针对性地复习相关知识点。</p>");
        sb.append("<p style=\"margin-top:12px\"><a href=\"").append(escapeHtml(frontendBaseUrl)).append("\">📱 点击前往智能实验辅助系统 →</a></p>");
        sb.append("<p class=\"signature\">—— 智能实验辅助系统</p>");
        sb.append("</div>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static List<String> collectDistinctActions(List<ProblemWarningInfo> problems) {
        List<String> result = new ArrayList<>();
        for (ProblemWarningInfo p : problems) {
            if (p.getSuggestedActions() != null) {
                for (String a : p.getSuggestedActions()) {
                    if (!result.contains(a)) result.add(a);
                }
            }
        }
        return result;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void appendStatusBadges(StringBuilder sb, Map<String, Integer> statusCounts) {
        if (statusCounts == null || statusCounts.isEmpty()) return;
        sb.append(" <span style=\"font-size:11px\">");
        for (Map.Entry<String, Integer> e : statusCounts.entrySet()) {
            String color = statusColor(e.getKey());
            sb.append("<span class=\"status-badge\" style=\"background:").append(color).append("\">");
            sb.append(ProblemWarningInfo.statusLabel(e.getKey())).append(" ").append(e.getValue()).append("次");
            sb.append("</span>");
        }
        sb.append("</span>");
    }

    private static String statusColor(String status) {
        switch (status != null ? status.toUpperCase() : "") {
            case "COMPILE_ERROR": return "#fce8e6;color:#c5221f";
            case "RUNTIME_ERROR": return "#fef7e0;color:#e37400";
            case "WRONG_ANSWER": return "#f1f3f4;color:#5f6368";
            case "TIME_LIMIT_EXCEEDED": return "#fef7e0;color:#e37400";
            case "MEMORY_LIMIT_EXCEEDED": return "#e8f0fe;color:#174ea6";
            case "SEGMENTATION_FAULT": return "#fce8e6;color:#c5221f";
            default: return "#f1f3f4;color:#666";
        }
    }

    private static List<ProblemWarningInfo> collectAllProblems(List<ExperimentWarningSummary> experiments) {
        List<ProblemWarningInfo> all = new ArrayList<>();
        for (ExperimentWarningSummary exp : experiments) {
            if (exp.getProblems() != null) all.addAll(exp.getProblems());
        }
        return all;
    }

    private String buildMultiProblemEmailBody(String studentName,
                                               String experimentName,
                                               List<ProblemWarningInfo> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>").append(EMAIL_CSS).append("</style></head><body>");
        sb.append("<div class=\"wrap\">");
        sb.append("<p class=\"greeting\">").append(escapeHtml(studentName)).append(" 同学：</p>");

        sb.append("<div class=\"exp-block\">");
        sb.append("<p class=\"exp-name\">▶ ").append(escapeHtml(experimentName)).append("</p>");
        for (int i = 0; i < problems.size(); i++) {
            ProblemWarningInfo p = problems.get(i);
            sb.append("<p class=\"prob-item\">");
            sb.append("<span class=\"prob-title\">").append(i + 1).append(".「").append(escapeHtml(p.getProblemTitle())).append("」</span>");
            sb.append(" <span class=\"stat-summary\">：共提交 ").append(p.getTotalSubmissions()).append(" 次，通过 ").append(p.getAcceptedCount()).append(" 次</span>");
            appendStatusBadges(sb, p.getStatusCounts());
            sb.append("</p>");
        }
        sb.append("</div>");

        List<String> actions = collectDistinctActions(problems);
        if (!actions.isEmpty()) {
            sb.append("<div class=\"actions-block\">");
            sb.append("<p class=\"actions-title\">💡 建议行动</p>");
            for (int i = 0; i < actions.size(); i++) {
                sb.append("<p class=\"action-item\">").append(i + 1).append(". ").append(escapeHtml(actions.get(i))).append("</p>");
            }
            sb.append("</div>");
        }

        sb.append("<div class=\"footer\">");
        sb.append("<p>提示：本邮件由系统自动发送，同一实验 1 小时内仅提醒一次。</p>");
        sb.append("<p style=\"margin-top:12px\"><a href=\"").append(escapeHtml(frontendBaseUrl)).append("\">📱 点击前往智能实验辅助系统 →</a></p>");
        sb.append("<p class=\"signature\">—— 智能实验辅助系统</p>");
        sb.append("</div>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

}
