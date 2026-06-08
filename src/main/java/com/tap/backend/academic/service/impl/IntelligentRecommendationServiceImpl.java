package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.entity.LeetCodeRecommendItem;
import com.tap.backend.academic.entity.LeetCodeRecommendRequest;
import com.tap.backend.academic.service.LeetCodeRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service("intelligentRecommendationService")
public class IntelligentRecommendationServiceImpl implements LeetCodeRecommendationService {

    private static final Logger logger = LoggerFactory.getLogger(IntelligentRecommendationServiceImpl.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final String recommendationBaseUrl;

    public IntelligentRecommendationServiceImpl(
            @Value("${tap.recommendation.base-url:http://127.0.0.1:8003}") String recommendationBaseUrl,
            @Value("${tap.recommendation.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${tap.recommendation.read-timeout-ms:10000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(requestFactory);
        this.recommendationBaseUrl = recommendationBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public String generateRecommendation(Integer studentId, Integer limit, String scene) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("studentId", requireStudentId(studentId));
        payload.put("limit", normalizeLimit(limit));
        payload.put("scene", normalizeScene(scene));

        Map<String, Object> data = postForData("/ai/recommendation/generate", payload);
        String requestId = asString(data.get("requestId"));
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalStateException("recommendation-service returned empty requestId");
        }
        return requestId;
    }

    @Override
    public LeetCodeRecommendRequest getRecommendationResult(String requestId) {
        String normalizedRequestId = requireText(requestId, "requestId");
        try {
            Map<String, Object> data = getForData("/ai/recommendation/result/" + normalizedRequestId);
            return mapRequest(data);
        } catch (HttpClientErrorException.NotFound notFound) {
            return null;
        }
    }

    @Override
    public List<LeetCodeRecommendItem> getRecommendationItems(String requestId) {
        LeetCodeRecommendRequest request = getRecommendationResult(requestId);
        if (request == null || !request.isCompleted()) {
            return List.of();
        }
        Map<String, Object> data = getForData("/ai/recommendation/result/" + requireText(requestId, "requestId"));
        return mapItems(asMapList(data.get("items")), request.getRequestId());
    }

    @Override
    public List<LeetCodeRecommendItem> generateRecommendationSync(Integer studentId, Integer limit) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("studentId", requireStudentId(studentId));
        payload.put("limit", normalizeLimit(limit));

        Map<String, Object> data = postForData("/ai/recommendation/sync", payload);
        return mapItems(asMapList(data.get("items")), asString(data.get("requestId")));
    }

    @Override
    public boolean recordFeedback(String requestId, Integer studentId, Long problemId, String action, String sessionId) {
        String normalizedAction = requireText(action, "action").toLowerCase();
        String normalizedRequestId = requireText(requestId, "requestId");
        long normalizedProblemId = requireProblemId(problemId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", normalizedRequestId);
        payload.put("problemId", normalizedProblemId);
        if (sessionId != null && !sessionId.isBlank()) {
            payload.put("sessionId", sessionId.trim());
        }

        String path;
        if ("exposure".equals(normalizedAction)) {
            path = "/ai/recommendation/exposure";
        } else {
            path = "/ai/recommendation/feedback";
            payload.put("action", normalizedAction);
        }

        Map<String, Object> data = postForData(path, payload);
        Object success = data.get("success");
        return success instanceof Boolean ? (Boolean) success : Boolean.parseBoolean(String.valueOf(success));
    }

    private Map<String, Object> postForData(String path, Map<String, Object> payload) {
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(buildUrl(path), jsonEntity(payload), Map.class);
            return unwrapData(response.getBody());
        } catch (RestClientException e) {
            logger.error("Call recommendation-service failed. path={} payload={}", path, payload, e);
            throw new IllegalStateException("recommendation-service call failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> getForData(String path) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(buildUrl(path), HttpMethod.GET, jsonEntity(null), Map.class);
            return unwrapData(response.getBody());
        } catch (RestClientException e) {
            logger.error("Call recommendation-service failed. path={}", path, e);
            throw e;
        }
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(payload, headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapData(Map responseBody) {
        if (responseBody == null) {
            throw new IllegalStateException("recommendation-service returned empty response");
        }
        Object code = responseBody.get("code");
        if (code != null && !"200".equals(String.valueOf(code))) {
            throw new IllegalStateException("recommendation-service returned error code " + code + ": " + responseBody.get("message"));
        }
        Object data = responseBody.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return new HashMap<>();
        }
        return (Map<String, Object>) dataMap;
    }

    private LeetCodeRecommendRequest mapRequest(Map<String, Object> data) {
        LeetCodeRecommendRequest request = new LeetCodeRecommendRequest();
        request.setRequestId(asString(data.get("requestId")));
        request.setStudentId(asInteger(data.get("studentId")));
        request.setScene(asString(data.get("scene")));
        request.setRequestLimit(asInteger(data.get("requestLimit")));
        request.setStatus(asString(data.get("status")));
        request.setErrorMessage(asString(data.get("errorMessage")));
        request.setCreatedAt(parseDateTime(data.get("createdAt")));
        request.setFinishedAt(parseDateTime(data.get("finishedAt")));
        return request;
    }

    private List<LeetCodeRecommendItem> mapItems(List<Map<String, Object>> rawItems, String fallbackRequestId) {
        List<LeetCodeRecommendItem> items = new ArrayList<>();
        for (Map<String, Object> row : rawItems) {
            LeetCodeRecommendItem item = new LeetCodeRecommendItem();
            item.setRequestId(firstNonBlank(asString(row.get("requestId")), fallbackRequestId));
            item.setRankNo(asInteger(row.get("rankNo")));
            item.setProblemId(asLong(row.get("problemId")));
            item.setScoreTotal(asDecimal(row.get("scoreTotal")));
            item.setScoreNeedMatch(asDecimal(row.get("scoreNeedMatch")));
            item.setScoreDifficultyFit(asDecimal(row.get("scoreDifficultyFit")));
            item.setScoreSuccessProb(asDecimal(row.get("scoreSuccessProb")));
            item.setScoreNovelty(asDecimal(row.get("scoreNovelty")));
            item.setScoreQuality(asDecimal(row.get("scoreQuality")));
            item.setReasonText(asString(row.get("reasonText")));
            item.setProblem(mapProblem(asMap(row.get("problem"))));
            items.add(item);
        }
        return items;
    }

    private LeetCodeProblem mapProblem(Map<String, Object> rawProblem) {
        if (rawProblem.isEmpty()) {
            return null;
        }
        LeetCodeProblem problem = new LeetCodeProblem();
        problem.setId(asLong(rawProblem.get("problemId")));
        problem.setTitleMain(asString(rawProblem.get("title")));
        problem.setDifficulty(asString(rawProblem.get("difficulty")));
        problem.setSourceUrl(asString(rawProblem.get("sourceUrl")));
        problem.setEstimatedMinutes(asInteger(rawProblem.get("estimatedMinutes")));
        return problem;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                mapped.add((Map<String, Object>) map);
            }
        }
        return mapped;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String buildUrl(String path) {
        return recommendationBaseUrl + path;
    }

    private Integer requireStudentId(Integer studentId) {
        if (studentId == null || studentId <= 0) {
            throw new IllegalArgumentException("studentId is invalid");
        }
        return studentId;
    }

    private long requireProblemId(Long problemId) {
        if (problemId == null || problemId <= 0) {
            throw new IllegalArgumentException("problemId is invalid");
        }
        return problemId;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String normalizeScene(String scene) {
        return (scene == null || scene.isBlank()) ? "default" : scene.trim();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.max(1, Math.min(limit, 50));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(Object value) {
        String text = asString(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignore) {
            try {
                return LocalDateTime.parse(text, DATE_TIME_FORMATTER);
            } catch (DateTimeParseException ignoredAgain) {
                logger.warn("Unable to parse recommendation datetime: {}", text);
                return null;
            }
        }
    }
}
