package com.tap.backend.academic.service;

import java.util.Map;

public record AiReportResult(
        boolean success,
        String message,
        String report,
        Map<String, Object> data) {

    public static AiReportResult failure(String message) {
        return new AiReportResult(false, message, "", Map.of());
    }
}
