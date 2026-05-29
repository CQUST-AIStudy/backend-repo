package com.tap.backend.academic.leetcode.execution;

public record CodeExecutionResult(
        boolean success,
        String output,
        String error,
        long runtime
) {
    public static CodeExecutionResult success(String output, long runtime) {
        return new CodeExecutionResult(true, output == null ? "" : output, "", runtime);
    }

    public static CodeExecutionResult failure(String error) {
        return new CodeExecutionResult(false, "", error == null ? "" : error, 0L);
    }
}
