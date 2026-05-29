package com.tap.backend.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class PtaCookieService {

    private static final Logger log = LoggerFactory.getLogger(PtaCookieService.class);

    private final RestTemplate restTemplate;

    @Value("${pta.spider-url:http://127.0.0.1:8100}")
    private String spiderUrl;

    private volatile String cookieStatus = "UNKNOWN";
    private volatile String cookieError = "";
    private volatile Instant lastUpdated;

    public PtaCookieService(
            @Value("${pta.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${pta.read-timeout-ms:20000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(1000, readTimeoutMs));
        this.restTemplate = new RestTemplate(requestFactory);
    }

    public synchronized void reportStatus(String status, String error) {
        this.cookieStatus = status != null && !status.isBlank() ? status : "UNKNOWN";
        this.cookieError = error != null ? error : "";
        this.lastUpdated = Instant.now();
        log.info("PTA cookie status updated: {} {}", this.cookieStatus, this.cookieError);
    }

    public synchronized Map<String, Object> getStatusSnapshot() {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(spiderUrl + "/cookie/status", Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                syncFromSpiderBody(response.getBody());
            }
        } catch (Exception ex) {
            log.debug("Failed to query PTA spider cookie status: {}", ex.getMessage());
        }
        return snapshot();
    }

    public synchronized Map<String, Object> submitCookie(String cookies) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of("cookies", cookies), headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(spiderUrl + "/cookie/update", entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                boolean valid = Boolean.TRUE.equals(body.get("valid"));
                if (valid) {
                    this.cookieStatus = "OK";
                    this.cookieError = "";
                    this.lastUpdated = Instant.now();
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("valid", valid);
                result.put("message", body.get("message"));
                result.put("status", this.cookieStatus);
                result.put("error", this.cookieError);
                result.put("lastUpdated", this.lastUpdated);
                return result;
            }
            throw new RuntimeException("pta spider returned an unexpected response");
        } catch (Exception ex) {
            log.error("Failed to submit PTA cookie: {}", ex.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid", false);
            result.put("message", "pta spider unavailable: " + ex.getMessage());
            result.put("status", this.cookieStatus);
            result.put("error", this.cookieError);
            result.put("lastUpdated", this.lastUpdated);
            return result;
        }
    }

    private void syncFromSpiderBody(Map<?, ?> body) {
        Object statusValue = body.containsKey("status") ? body.get("status") : "UNKNOWN";
        Object errorValue = body.containsKey("error") ? body.get("error") : "";
        this.cookieStatus = String.valueOf(statusValue);
        this.cookieError = String.valueOf(errorValue);
        Object lastUpdatedValue = body.get("lastUpdated");
        if (lastUpdatedValue instanceof String text && !text.isBlank()) {
            try {
                this.lastUpdated = Instant.parse(text);
                return;
            } catch (Exception ignored) {
                // Fall back to server receive time.
            }
        }
        if (lastUpdatedValue instanceof Instant instant) {
            this.lastUpdated = instant;
        } else {
            this.lastUpdated = Instant.now();
        }
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", this.cookieStatus);
        result.put("error", this.cookieError);
        result.put("lastUpdated", this.lastUpdated);
        return result;
    }
}
