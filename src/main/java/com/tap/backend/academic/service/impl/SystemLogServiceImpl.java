package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.service.SystemLogService;
import com.tap.backend.audit.AuditEventEntity;
import com.tap.backend.audit.AuditEventRepository;
import com.tap.backend.repo.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SystemLogServiceImpl implements SystemLogService {

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private UserRepository userRepository;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public Map<String, Object> getLogPage(int page, int pageSize, String keyword, String level) {
        // 1. 查出全部日志（按时间倒序）
        List<AuditEventEntity> allLogs = auditEventRepository.findAll(
                Sort.by(Sort.Direction.DESC, "createdAt"));

        // 2. 内存过滤
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;
        String lv = (level != null && !level.isBlank()) ? level.trim().toUpperCase() : null;

        List<Map<String, Object>> filtered = allLogs.stream()
                .map(this::toLogRecord)
                .filter(record -> {
                    // 按关键词筛选（匹配操作人、操作内容、分类）
                    if (kw != null) {
                        String user = String.valueOf(record.getOrDefault("user", "")).toLowerCase();
                        String msg = String.valueOf(record.getOrDefault("message", "")).toLowerCase();
                        String cat = String.valueOf(record.getOrDefault("category", "")).toLowerCase();
                        if (!user.contains(kw) && !msg.contains(kw) && !cat.contains(kw)) {
                            return false;
                        }
                    }
                    // 按日志级别筛选
                    if (lv != null) {
                        String recordLevel = String.valueOf(record.getOrDefault("level", ""));
                        if (!lv.equals(recordLevel)) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // 3. 手动分页
        int total = filtered.size();
        int fromIndex = (page - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageRecords = (fromIndex < total)
                ? filtered.subList(fromIndex, toIndex)
                : Collections.emptyList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", pageRecords);
        response.put("total", total);
        return response;
    }

    @Override
    @Transactional
    public void clearAll() {
        auditEventRepository.deleteAllInBatch();
    }

    @Override
    public void exportCsv(HttpServletResponse response) throws Exception {
        List<AuditEventEntity> allLogs = auditEventRepository.findAll(
                Sort.by(Sort.Direction.DESC, "createdAt"));

        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=system-logs.csv");

        PrintWriter writer = response.getWriter();
        writer.write('﻿'); // BOM for Excel UTF-8
        writer.println("时间,操作,类型,目标,IP地址,User-Agent");

        for (AuditEventEntity log : allLogs) {
            writer.printf("%s,%s,%s,%s,%s,%s%n",
                    csvEscape(formatTimestamp(log.getCreatedAt())),
                    csvEscape(log.getAction()),
                    csvEscape(log.getTargetType()),
                    csvEscape(log.getTargetId()),
                    csvEscape(log.getIp()),
                    csvEscape(log.getUserAgent()));
        }
        writer.flush();
    }

    // ---- 私有辅助 ----

    private Map<String, Object> toLogRecord(AuditEventEntity e) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", e.getId());
        record.put("timestamp", formatTimestamp(e.getCreatedAt()));
        record.put("level", deriveLevel(e.getAction()));
        record.put("category", e.getTargetType() != null ? e.getTargetType() : "操作");
        record.put("message", e.getAction() != null ? e.getAction() : "");
        record.put("user", resolveUsername(e.getUserId()));
        record.put("ip", e.getIp() != null ? e.getIp() : "");
        record.put("userAgent", e.getUserAgent() != null ? e.getUserAgent() : "");
        record.put("url", "");
        record.put("params", e.getMetadataJson() != null ? e.getMetadataJson() : "");
        record.put("stackTrace", "");
        return record;
    }

    /** 根据操作类型推导日志级别 */
    private String deriveLevel(String action) {
        if (action == null) return "INFO";
        if (action.contains("删除") || action.contains("清空") || action.contains("异常")) return "ERROR";
        if (action.contains("修改") || action.contains("更新")) return "WARNING";
        return "INFO";
    }

    private String resolveUsername(Long userId) {
        if (userId == null) return "";
        try {
            return userRepository.findById(userId)
                    .map(u -> u.getUsername())
                    .orElse(String.valueOf(userId));
        } catch (Exception e) {
            return String.valueOf(userId);
        }
    }

    private String formatTimestamp(Instant instant) {
        if (instant == null) return "";
        return TIMESTAMP_FMT.format(instant);
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
