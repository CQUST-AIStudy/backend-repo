package com.tap.backend.academic.service;

public class AiReportException extends RuntimeException {

    private final String errorCode;

    public AiReportException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AiReportException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }

    public boolean isConfigMissing() { return "CONFIG_MISSING".equals(errorCode); }

    public boolean isTimeout() { return "TIMEOUT".equals(errorCode); }

    public static AiReportException configMissing() {
        return new AiReportException("CONFIG_MISSING", "AI service is not configured");
    }

    public static AiReportException timeout(Throwable cause) {
        return new AiReportException("TIMEOUT", "AI service response timeout", cause);
    }

    public static AiReportException upstream(int httpStatus) {
        return new AiReportException("UPSTREAM_" + httpStatus,
                "AI upstream returned HTTP " + httpStatus);
    }

    public static AiReportException upstream(int httpStatus, String detail) {
        return new AiReportException("UPSTREAM_" + httpStatus,
                "AI upstream returned HTTP " + httpStatus + ": " + truncate(detail));
    }

    public static AiReportException emptyResponse() {
        return new AiReportException("UPSTREAM_EMPTY_BODY", "AI service returned empty response");
    }

    public static AiReportException badResponse(String detail) {
        return new AiReportException("UPSTREAM_BAD_RESPONSE",
                "AI service returned unparseable response: " + truncate(detail));
    }

    public boolean isAuthFailure() {
        return errorCode != null && (errorCode.contains("401") || errorCode.contains("403"));
    }

    public boolean isRateLimited() {
        return errorCode != null && errorCode.contains("429");
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "..." : s);
    }
}
