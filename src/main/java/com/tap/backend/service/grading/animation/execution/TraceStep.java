package com.tap.backend.service.grading.animation.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码执行轨迹中的单一步骤。
 *
 * @param step         步骤序号（从 1 开始）
 * @param line         源代码行号（从 1 开始）
 * @param event        事件类型：step / call / return / exception
 * @param stdout       截止到当前步骤的累积标准输出
 * @param locals       局部变量
 * @param globals      全局变量
 * @param heap         堆内存状态
 * @param error        是否触发错误
 * @param errorMessage 错误信息
 */
public record TraceStep(
        int step,
        int line,
        String event,
        String stdout,
        Map<String, Object> locals,
        Map<String, Object> globals,
        Map<String, Object> heap,
        boolean error,
        String errorMessage
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("order", step);
        map.put("line", line);
        map.put("event", event);
        map.put("stdout", stdout);
        // 兼容前端播放器字段命名
        map.put("variables", locals);
        map.put("globals", globals);
        map.put("heap", heap);
        map.put("state", buildState());
        map.put("memory", buildMemory());
        map.put("error", error);
        map.put("errorMessage", errorMessage);
        map.put("explanation", buildExplanation());
        return map;
    }

    private Map<String, Object> buildState() {
        // 为 PythonTutorRenderer 提供可视化的 nodes/edges
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("dataStructure", inferDataStructure());
        state.put("nodes", inferNodes());
        state.put("edges", List.of());
        return state;
    }

    private String inferDataStructure() {
        // 启发式推断当前步骤最适合的可视化结构
        for (String key : locals.keySet()) {
            Object val = locals.get(key);
            if (val instanceof Map<?, ?> m && "array".equals(m.get("type"))) {
                return "array";
            }
            if (key.startsWith("ptr") || key.equals("p") || key.endsWith("Ptr")) {
                return "pointer";
            }
        }
        return "code";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> inferNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Object> entry : locals.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> m && "array".equals(m.get("type"))) {
                List<Object> values = (List<Object>) m.get("values");
                Integer size = m.get("size") instanceof Number ? ((Number) m.get("size")).intValue() : (values != null ? values.size() : 0);
                for (int i = 0; i < size && values != null && i < values.size(); i++) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", key + i);
                    node.put("label", key + "[" + i + "]");
                    node.put("value", String.valueOf(values.get(i)));
                    node.put("active", i == (size - 1));
                    node.put("outOfBounds", i >= size);
                    node.put("index", i);
                    nodes.add(node);
                }
            } else {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", key);
                node.put("label", key);
                node.put("value", val == null ? "NULL" : String.valueOf(val));
                node.put("active", index == 0);
                node.put("outOfBounds", false);
                node.put("index", index);
                nodes.add(node);
            }
            index++;
        }
        return nodes;
    }

    private List<Map<String, Object>> buildMemory() {
        // 为旧版播放器提供 memory 数组（数组元素）
        List<Map<String, Object>> memory = new ArrayList<>();
        for (Map.Entry<String, Object> entry : locals.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> m && "array".equals(m.get("type"))) {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) m.get("values");
                if (values != null) {
                    for (int i = 0; i < values.size(); i++) {
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("label", entry.getKey() + "[" + i + "]");
                        cell.put("value", String.valueOf(values.get(i)));
                        cell.put("active", i == values.size() - 1);
                        cell.put("outOfBounds", false);
                        memory.add(cell);
                    }
                }
            }
        }
        return memory;
    }

    private String buildExplanation() {
        if (error && errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        if (error) {
            return "程序在此处触发错误";
        }
        return String.format("执行到第 %d 行，当前变量状态如右图所示", line);
    }
}
