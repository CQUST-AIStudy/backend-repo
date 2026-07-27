package com.tap.backend.service.grading.animation.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 通用代码执行沙箱服务。
 * <p>
 * 通过调用本地 Python3.11 脚本 {@code code_tracer.py} 真实执行学生代码，
 * 并返回 Python Tutor 风格的执行轨迹 JSON。
 * <p>
 * 当前支持：
 * <ul>
 *   <li>C 语言：gcc 编译 + 源码级 instrumentation</li>
 *   <li>Python：sys.settrace 捕获执行过程</li>
 * </ul>
 */
@Service
public class CodeExecutionSandboxService {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionSandboxService.class);

    private final ObjectMapper objectMapper;
    private final String pythonExecutable;
    private final String tracerScriptPath;
    private final long defaultTimeoutMs;
    /** 已探测到的可用 python 命令；空串表示探测过但不可用 */
    private volatile String resolvedPython;
    /** 按语言缓存可用性检查结果，避免每个演示候选都重复起进程探测 */
    private final Map<String, Boolean> availabilityCache = new ConcurrentHashMap<>();

    public CodeExecutionSandboxService(ObjectMapper objectMapper,
                                       @Value("${tap.grading.code-tracer.python:}") String pythonExecutable,
                                       @Value("${tap.grading.code-tracer.script:}") String tracerScriptPath,
                                       @Value("${tap.grading.code-tracer.timeout-ms:10000}") long defaultTimeoutMs) {
        this.objectMapper = objectMapper;
        this.pythonExecutable = pythonExecutable;
        this.tracerScriptPath = tracerScriptPath;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /**
     * 执行代码并返回执行轨迹。
     *
     * @param language   语言："c" 或 "python"
     * @param sourceCode 源代码字符串
     * @param stdinText  标准输入（可选）
     * @return 执行轨迹结果
     */
    public ExecutionTrace execute(String language, String sourceCode, String stdinText) {
        return execute(language, sourceCode, stdinText, defaultTimeoutMs);
    }

    public ExecutionTrace execute(String language, String sourceCode, String stdinText, long timeoutMs) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return ExecutionTrace.failed(language, sourceCode, "Source code is empty");
        }

        String normalizedLang = normalizeLanguage(language);
        if (!"c".equals(normalizedLang) && !"python".equals(normalizedLang)) {
            return ExecutionTrace.failed(language, sourceCode,
                    "Unsupported language: " + language + " (only c and python are supported)");
        }

        Path tracerScript = resolveTracerScript();
        if (tracerScript == null) {
            return ExecutionTrace.failed(language, sourceCode,
                    "Code tracer script not found. Please configure tap.grading.code-tracer.script");
        }

        String python = resolvePythonExecutable();
        if (python == null) {
            return ExecutionTrace.failed(language, sourceCode,
                    "No usable python executable found. Please configure tap.grading.code-tracer.python");
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("code-tracer-");
            Path sourceFile = tmpDir.resolve("source." + fileExtension(normalizedLang));
            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

            List<String> command = new ArrayList<>();
            command.add(python);
            command.add(tracerScript.toAbsolutePath().toString());
            command.add(normalizedLang);
            command.add(sourceFile.toAbsolutePath().toString());
            if (stdinText != null && !stdinText.isBlank()) {
                command.add("--stdin");
                command.add(stdinText);
            }

            log.debug("Running code tracer: {}", command);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ExecutionTrace.failed(language, sourceCode,
                        "Code execution timeout (possible infinite loop)");
            }

            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return ExecutionTrace.failed(language, sourceCode,
                        "Code tracer exited with code " + process.exitValue() + ": " + output);
            }

            return parseTrace(output, normalizedLang, sourceCode);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionTrace.failed(language, sourceCode,
                    "Failed to run code tracer: " + e.getMessage());
        } finally {
            if (tmpDir != null) {
                deleteQuietly(tmpDir);
            }
        }
    }

    /**
     * 检查当前环境是否可以执行指定语言的代码。
     * <p>结果按语言缓存，进程探测只做一次；环境变化后需重启应用刷新。</p>
     */
    public boolean isAvailable(String language) {
        String normalized = normalizeLanguage(language);
        if (!"c".equals(normalized) && !"python".equals(normalized)) {
            return false;
        }
        return availabilityCache.computeIfAbsent(normalized, this::checkAvailability);
    }

    private boolean checkAvailability(String language) {
        if (resolveTracerScript() == null) {
            log.info("Code tracer unavailable: tracer script not found");
            return false;
        }
        if (resolvePythonExecutable() == null) {
            log.info("Code tracer unavailable: no usable python executable");
            return false;
        }
        if ("c".equals(language) && !commandWorks("gcc", "--version")) {
            log.info("Code tracer unavailable for c: gcc not found");
            return false;
        }
        return true;
    }

    /**
     * 解析可用的 python 命令：优先使用配置值，否则依次探测常见命令。
     */
    private String resolvePythonExecutable() {
        String cached = resolvedPython;
        if (cached != null) {
            return cached.isBlank() ? null : cached;
        }
        List<String> candidates = new ArrayList<>();
        if (pythonExecutable != null && !pythonExecutable.isBlank()) {
            candidates.add(pythonExecutable.trim());
        }
        candidates.add("python3.11");
        candidates.add("python3");
        candidates.add("python");
        for (String candidate : candidates) {
            if (commandWorks(candidate, "--version")) {
                resolvedPython = candidate;
                log.info("Code tracer python executable resolved: {}", candidate);
                return candidate;
            }
        }
        resolvedPython = "";
        return null;
    }

    private boolean commandWorks(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private ExecutionTrace parseTrace(String jsonOutput, String language, String sourceCode) {
        try {
            JsonNode root = objectMapper.readTree(jsonOutput);
            boolean success = root.path("success").asBoolean(false);
            String errorMessage = root.path("errorMessage").asText("");
            String stdout = root.path("stdout").asText("");
            String stderr = root.path("stderr").asText("");

            if (!success) {
                return ExecutionTrace.failed(language, sourceCode,
                        errorMessage.isBlank() ? "Execution failed" : errorMessage);
            }

            List<TraceStep> steps = new ArrayList<>();
            JsonNode stepsNode = root.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    steps.add(parseStep(stepNode));
                }
            }

            return new ExecutionTrace(
                    true,
                    language,
                    sourceCode,
                    errorMessage,
                    stdout,
                    stderr,
                    steps
            );
        } catch (Exception e) {
            log.warn("Failed to parse tracer output: {}", e.getMessage());
            return ExecutionTrace.failed(language, sourceCode,
                    "Failed to parse execution trace: " + e.getMessage());
        }
    }

    private TraceStep parseStep(JsonNode node) {
        int step = node.path("step").asInt(0);
        int line = node.path("line").asInt(0);
        String stdout = node.path("stdout").asText("");
        String event = node.path("event").asText("step");
        boolean error = node.path("error").asBoolean(false);
        String errorMessage = node.path("errorMessage").asText("");

        Map<String, Object> locals = parseVariables(node.path("locals"));
        Map<String, Object> globals = parseVariables(node.path("globals"));
        Map<String, Object> heap = parseVariables(node.path("heap"));

        return new TraceStep(step, line, event, stdout, locals, globals, heap, error, errorMessage);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseVariables(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(entry -> {
            result.put(entry.getKey(), convertJsonValue(entry.getValue()));
        });
        return result;
    }

    private Object convertJsonValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(convertJsonValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), convertJsonValue(entry.getValue()));
            });
            return map;
        }
        return node.toString();
    }

    private Path resolveTracerScript() {
        if (tracerScriptPath != null && !tracerScriptPath.isBlank()) {
            Path path = Path.of(tracerScriptPath);
            if (Files.exists(path)) {
                return path;
            }
        }
        // 默认查找路径（支持从 backend-repo、项目根目录或 Docker 容器 /app 启动）
        String[] candidates = {
                "grading-worker/code_tracer.py",
                "backend-repo/grading-worker/code_tracer.py",
                "../backend-repo/grading-worker/code_tracer.py",
                "/app/tools/code_tracer.py",
                "_inspect_grading_worker/grading_worker/code_tracer.py",
                "../_inspect_grading_worker/grading_worker/code_tracer.py"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String lower = language.toLowerCase().trim();
        if (lower.startsWith("c") || lower.equals("cpp") || lower.equals("c++")) {
            return "c";
        }
        if (lower.equals("py") || lower.equals("python")) {
            return "python";
        }
        return lower;
    }

    private static String fileExtension(String language) {
        return "c".equals(language) ? "c" : "py";
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(CodeExecutionSandboxService::deleteQuietly);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
