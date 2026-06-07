package com.tap.backend.academic.service;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * 系统日志服务
 */
public interface SystemLogService {

    /**
     * 分页查询日志（支持操作人和级别筛选）
     */
    Map<String, Object> getLogPage(int page, int pageSize, String keyword, String level);

    /**
     * 清空所有日志
     */
    void clearAll();

    /**
     * 导出日志为 CSV
     */
    void exportCsv(HttpServletResponse response) throws Exception;
}
