package com.tap.backend.service.grading;

import com.tap.backend.academic.dao.StudentExperimentReflectionDao;
import com.tap.backend.academic.entity.StudentExperimentReflection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 学生实验心得的持久化读写服务；教师端只读取数据库，不在请求时拼接心得。 */
@Service
public class StudentReflectionService {

    private static final Pattern SUMMARY_HEADING = Pattern.compile(
            "(?im)^\\s{0,3}(?:#{1,6}\\s*)?(?:[一二三四五六七八九十0-9]+[、.．]\\s*)?(?:实验总结|心得体会|实验心得)\\s*(?:[:：].*)?$");
    private static final Pattern NEXT_HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+.+$");

    private final StudentExperimentReflectionDao reflectionDao;

    public StudentReflectionService(StudentExperimentReflectionDao reflectionDao) {
        this.reflectionDao = reflectionDao;
    }

    public Map<String, Object> find(Long offeringId, Long studentId) {
        StudentExperimentReflection reflection = reflectionDao.findByOfferingAndStudent(offeringId, studentId);
        if (reflection == null || reflection.getReflectionText() == null
                || reflection.getReflectionText().isBlank()) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("content", reflection.getReflectionText());
        view.put("source", reflection.getSource());
        view.put("sourceLabel", sourceLabel(reflection.getSource()));
        view.put("updatedAt", reflection.getUpdatedAt() == null
                ? null
                : reflection.getUpdatedAt().toInstant().toString());
        return view;
    }

    public void saveFromAiReport(long offeringId, long studentId, String reportMarkdown) {
        String summary = extractSummary(reportMarkdown);
        if (!summary.isBlank()) {
            reflectionDao.upsert(offeringId, studentId, summary, "AI_REPORT");
        }
    }

    static String extractSummary(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Matcher heading = SUMMARY_HEADING.matcher(markdown);
        if (!heading.find()) return "";
        int start = heading.end();
        Matcher next = NEXT_HEADING.matcher(markdown);
        next.region(start, markdown.length());
        int end = next.find() ? next.start() : markdown.length();
        return markdown.substring(start, end)
                .replaceFirst("^[\\s:：-]+", "")
                .trim();
    }

    private String sourceLabel(String source) {
        if ("AI_REPORT".equalsIgnoreCase(source)) return "AI 报告 · 实验总结";
        if ("SYSTEM_BACKFILL".equalsIgnoreCase(source)) return "系统补充心得 · 已保存";
        if ("STUDENT_EDITED".equalsIgnoreCase(source)) return "学生填写";
        return "学生实验心得";
    }
}
