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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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

    private void sendEmail(String to, String subject, String body) {
        if (mailSender == null) {
            logger.warn("SKIP: JavaMailSender not configured (mailSender is null)");
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            logger.info("SUCCESS: Email sent to={}, subject={}", to, subject);
        } catch (Exception e) {
            logger.error("FAILED to send email: {}", e.getMessage(), e);
        }
    }

    // ==================== 邮件内容构建 ====================

    private String buildMultiProblemEmailBody(String studentName,
                                               String experimentName,
                                               List<ProblemWarningInfo> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s 同学：\n\n", studentName));

        if (problems.size() == 1) {
            ProblemWarningInfo p = problems.get(0);
            sb.append(String.format("您在《%s》的题目「%s」中触发预警。\n\n", experimentName, p.getProblemTitle()));
            appendProblemDetail(sb, p);
        } else {
            sb.append(String.format("您在《%s》中有以下题目触发预警：\n\n", experimentName));
            for (int i = 0; i < problems.size(); i++) {
                ProblemWarningInfo p = problems.get(i);
                sb.append(String.format("%d. 「%s」：共提交 %d 次，通过 %d 次",
                        i + 1, p.getProblemTitle(), p.getTotalSubmissions(), p.getAcceptedCount()));
                appendStatusCounts(sb, p.getStatusCounts());
                sb.append("\n");
            }
            sb.append("\n");
            appendCombinedActions(sb, problems);
        }

        sb.append("提示：本邮件由系统自动发送，同一实验 1 小时内仅提醒一次。\n");
        sb.append("请根据上述错误类型有针对性地复习相关知识点。\n\n");
        sb.append("—— 智能实验辅助系统");

        return sb.toString();
    }

    private void appendProblemDetail(StringBuilder sb, ProblemWarningInfo p) {
        sb.append(String.format("提交概况：共提交 %d 次，通过 %d 次",
                p.getTotalSubmissions(), p.getAcceptedCount()));
        appendStatusCounts(sb, p.getStatusCounts());
        sb.append("\n");
        appendActions(sb, p.getSuggestedActions());
    }

    private void appendStatusCounts(StringBuilder sb, Map<String, Integer> statusCounts) {
        if (statusCounts == null || statusCounts.isEmpty()) return;
        sb.append("（");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : statusCounts.entrySet()) {
            parts.add(ProblemWarningInfo.statusLabel(e.getKey()) + " " + e.getValue() + " 次");
        }
        sb.append(String.join("、", parts));
        sb.append("）");
    }

    private void appendActions(StringBuilder sb, List<String> actions) {
        if (actions == null || actions.isEmpty()) return;
        sb.append("建议行动：\n");
        for (int i = 0; i < actions.size(); i++) {
            sb.append(String.format("  %d. %s\n", i + 1, actions.get(i)));
        }
        sb.append("\n");
    }

    private void appendCombinedActions(StringBuilder sb, List<ProblemWarningInfo> problems) {
        List<String> all = new ArrayList<>();
        for (ProblemWarningInfo p : problems) {
            if (p.getSuggestedActions() != null) {
                for (String a : p.getSuggestedActions()) {
                    if (!all.contains(a)) all.add(a);
                }
            }
        }
        if (!all.isEmpty()) {
            sb.append("建议行动：\n");
            for (int i = 0; i < all.size(); i++) {
                sb.append(String.format("  %d. %s\n", i + 1, all.get(i)));
            }
            sb.append("\n");
        }
    }

}
