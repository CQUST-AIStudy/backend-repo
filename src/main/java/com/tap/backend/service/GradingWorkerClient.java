package com.tap.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * 调用 Python Worker 暴露的 HTTP API（如评分表图片解析）。
 */
@Service
public class GradingWorkerClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GradingWorkerClient(
            @Value("${tap.grading.worker.base-url:http://127.0.0.1:8004}") String baseUrl,
            @Value("${tap.grading.worker.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${tap.grading.worker.read-timeout-ms:60000}") int readTimeoutMs,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 解析评分表图片，返回结构化维度列表。
     *
     * @param file 评分表图片
     * @return 解析结果，包含 rubricName、dimensions、totalScore 等
     */
    public ParseRubricResult parseRubricImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Rubric image is required");
        }

        String originalFilename = file.getOriginalFilename();
        ByteArrayResource resource;
        try {
            resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return originalFilename != null ? originalFilename : "rubric.png";
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read rubric image", e);
        }

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", resource);

        String raw = restClient.post()
                .uri("/parse-rubric-image")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(String.class);

        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Worker returned empty rubric parse response");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.has("error")) {
                String error = root.path("error").asText("unknown error");
                throw new IllegalStateException("Worker failed to parse rubric image: " + error);
            }
            return parseResult(root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse worker rubric response: " + raw, e);
        }
    }

    private ParseRubricResult parseResult(JsonNode root) {
        ParseRubricResult result = new ParseRubricResult();
        result.setRubricName(root.path("rubric_name").asText(""));
        result.setTotalScore(root.path("total_score").isNumber()
                ? root.path("total_score").decimalValue()
                : null);
        result.setConfidence(root.path("confidence").asDouble(0.0));
        result.setRawJson(root.toString());

        List<ParseRubricResult.Dimension> dimensions = new ArrayList<>();
        JsonNode dimsNode = root.path("dimensions");
        if (dimsNode.isArray()) {
            for (JsonNode dimNode : dimsNode) {
                ParseRubricResult.Dimension dim = new ParseRubricResult.Dimension();
                dim.setName(dimNode.path("name").asText(""));
                dim.setDescription(dimNode.path("description").asText(""));
                dim.setMaxScore(dimNode.path("max_score").isNumber()
                        ? dimNode.path("max_score").decimalValue()
                        : null);
                dim.setWeight(dimNode.path("weight").asInt(0));
                Map<String, String> levelRanges = new LinkedHashMap<>();
                JsonNode rangesNode = dimNode.path("level_ranges");
                if (rangesNode.isObject()) {
                    rangesNode.fields().forEachRemaining(entry ->
                            levelRanges.put(entry.getKey(), entry.getValue().asText("")));
                }
                dim.setLevelRanges(levelRanges);
                dim.setTeacherScore(dimNode.path("teacher_score").isNumber()
                        ? dimNode.path("teacher_score").decimalValue()
                        : null);
                dim.setTeacherLevel(dimNode.path("teacher_level").asText(null));
                dimensions.add(dim);
            }
        }
        result.setDimensions(dimensions);
        return result;
    }

    public static class ParseRubricResult {
        private String rubricName;
        private List<Dimension> dimensions = new ArrayList<>();
        private java.math.BigDecimal totalScore;
        private double confidence;
        private String rawJson;

        public String getRubricName() { return rubricName; }
        public void setRubricName(String rubricName) { this.rubricName = rubricName; }
        public List<Dimension> getDimensions() { return dimensions; }
        public void setDimensions(List<Dimension> dimensions) { this.dimensions = dimensions; }
        public java.math.BigDecimal getTotalScore() { return totalScore; }
        public void setTotalScore(java.math.BigDecimal totalScore) { this.totalScore = totalScore; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public String getRawJson() { return rawJson; }
        public void setRawJson(String rawJson) { this.rawJson = rawJson; }

        public static class Dimension {
            private String name;
            private String description;
            private java.math.BigDecimal maxScore;
            private int weight;
            private Map<String, String> levelRanges = new LinkedHashMap<>();
            private java.math.BigDecimal teacherScore;
            private String teacherLevel;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getDescription() { return description; }
            public void setDescription(String description) { this.description = description; }
            public java.math.BigDecimal getMaxScore() { return maxScore; }
            public void setMaxScore(java.math.BigDecimal maxScore) { this.maxScore = maxScore; }
            public int getWeight() { return weight; }
            public void setWeight(int weight) { this.weight = weight; }
            public Map<String, String> getLevelRanges() { return levelRanges; }
            public void setLevelRanges(Map<String, String> levelRanges) { this.levelRanges = levelRanges; }
            public java.math.BigDecimal getTeacherScore() { return teacherScore; }
            public void setTeacherScore(java.math.BigDecimal teacherScore) { this.teacherScore = teacherScore; }
            public String getTeacherLevel() { return teacherLevel; }
            public void setTeacherLevel(String teacherLevel) { this.teacherLevel = teacherLevel; }
        }
    }
}
