package com.tap.backend.academic.leetcode.execution;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class LeetCodeCodeExecutionEngine {

    private static final Map<String, LanguageConfig> LANGUAGE_CONFIGS = Map.of(
            "java", new LanguageConfig("Solution.java", true),
            "python", new LanguageConfig("solution.py", false),
            "c", new LanguageConfig("solution.c", true),
            "cpp", new LanguageConfig("solution.cpp", true),
            "javascript", new LanguageConfig("solution.js", false)
    );

    public String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if ("c++".equals(normalized) || "cplusplus".equals(normalized)) {
            return "cpp";
        }
        return normalized;
    }

    public boolean supports(String language) {
        return LANGUAGE_CONFIGS.containsKey(normalizeLanguage(language));
    }

    public CodeExecutionResult execute(String code, String language, String input) {
        String normalizedLanguage = normalizeLanguage(language);
        LanguageConfig config = LANGUAGE_CONFIGS.get(normalizedLanguage);
        if (config == null) {
            return CodeExecutionResult.failure("Unsupported language: " + language);
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("leetcode_exec_");
            Path sourceFile = tempDir.resolve(config.sourceFileName());
            Files.writeString(sourceFile, code == null ? "" : code, StandardCharsets.UTF_8);

            long start = System.currentTimeMillis();
            String executableName = isWindows() ? "solution.exe" : "solution";

            if (config.needsCompilation()) {
                ProcessBuilder compileBuilder = new ProcessBuilder();
                compileBuilder.directory(tempDir.toFile());
                if ("java".equals(normalizedLanguage)) {
                    compileBuilder.command("javac", config.sourceFileName());
                } else if ("c".equals(normalizedLanguage)) {
                    compileBuilder.command("gcc", "-std=c11", "-O2", "-o", executableName, config.sourceFileName());
                } else if ("cpp".equals(normalizedLanguage)) {
                    compileBuilder.command("g++", "-std=c++17", "-O2", "-o", executableName, config.sourceFileName());
                }

                Process compileProcess = compileBuilder.start();
                if (compileProcess.waitFor() != 0) {
                    return CodeExecutionResult.failure(readStream(compileProcess.getErrorStream()));
                }
            }

            ProcessBuilder runBuilder = new ProcessBuilder();
            runBuilder.directory(tempDir.toFile());
            if ("java".equals(normalizedLanguage)) {
                runBuilder.command("java", "Solution");
            } else if ("python".equals(normalizedLanguage)) {
                runBuilder.command("python", config.sourceFileName());
            } else if ("c".equals(normalizedLanguage) || "cpp".equals(normalizedLanguage)) {
                runBuilder.command(isWindows() ? executableName : "./" + executableName);
            } else if ("javascript".equals(normalizedLanguage)) {
                runBuilder.command("node", config.sourceFileName());
            }

            Process runProcess = runBuilder.start();
            if (input != null && !input.trim().isEmpty()) {
                try (PrintWriter writer = new PrintWriter(runProcess.getOutputStream())) {
                    writer.println(input);
                    writer.flush();
                }
            }

            if (!runProcess.waitFor(6, TimeUnit.SECONDS)) {
                runProcess.destroyForcibly();
                return CodeExecutionResult.failure("Execution timeout");
            }

            long runtime = System.currentTimeMillis() - start;
            if (runProcess.exitValue() == 0) {
                return CodeExecutionResult.success(readStream(runProcess.getInputStream()), runtime);
            }

            String error = readStream(runProcess.getErrorStream());
            if (error == null || error.isBlank()) {
                error = readStream(runProcess.getInputStream());
            }
            return CodeExecutionResult.failure(error);
        } catch (Exception e) {
            return CodeExecutionResult.failure("Execution error: " + e.getMessage());
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir.toFile());
            }
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    private record LanguageConfig(String sourceFileName, boolean needsCompilation) {
    }
}
