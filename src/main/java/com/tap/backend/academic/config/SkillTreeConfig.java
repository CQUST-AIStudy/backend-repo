package com.tap.backend.academic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * Externalized skill-tree configuration.
 * <p>
 * Defines the mapping between knowledge dimensions and experiment IDs,
 * as well as experiment display names. These were previously hardcoded
 * in {@link com.tap.backend.academic.service.ProfileService}.
 * <p>
 * Override via application.yml under {@code profile.skill-tree.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "profile.skill-tree")
public class SkillTreeConfig {

    /** Dimension name → list of experiment IDs */
    private Map<String, List<Integer>> dimensions = defaultDimensions();

    /** Dimension name → human-readable description */
    private Map<String, String> descriptions = defaultDescriptions();

    /** Experiment ID → display name */
    private Map<Integer, String> experimentNames = defaultExperimentNames();

    public Map<String, List<Integer>> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, List<Integer>> dimensions) {
        this.dimensions = dimensions;
    }

    public Map<String, String> getDescriptions() {
        return descriptions;
    }

    public void setDescriptions(Map<String, String> descriptions) {
        this.descriptions = descriptions;
    }

    public Map<Integer, String> getExperimentNames() {
        return experimentNames;
    }

    public void setExperimentNames(Map<Integer, String> experimentNames) {
        this.experimentNames = experimentNames;
    }

    public String getExperimentName(int experimentId) {
        return experimentNames.getOrDefault(experimentId, "实验" + experimentId);
    }

    public String getDimensionForExperiment(int experimentId) {
        for (var entry : dimensions.entrySet()) {
            if (entry.getValue().contains(experimentId)) {
                return entry.getKey();
            }
        }
        return "未知";
    }

    // ---- defaults (match the original hardcoded values) ----

    private static Map<String, List<Integer>> defaultDimensions() {
        Map<String, List<Integer>> m = new LinkedHashMap<>();
        m.put("线性表", List.of(1, 2, 3, 4, 5, 6, 7));
        m.put("栈与队列", List.of(8, 9, 15));
        m.put("树", List.of(10, 11, 12));
        m.put("图", List.of(14, 16));
        m.put("哈希", List.of(13));
        m.put("综合", List.of(17, 18, 19));
        return m;
    }

    private static Map<String, String> defaultDescriptions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("线性表", "顺序表、单链表、双向链表、循环链表等线性数据结构");
        m.put("栈与队列", "栈的实现与应用、队列的实现");
        m.put("树", "二叉搜索树、二叉树遍历、Huffman树");
        m.put("图", "DFS/BFS、Dijkstra/Prim最短路径与最小生成树");
        m.put("哈希", "哈希表的实现与冲突处理");
        m.put("综合", "综合练习与期中复习");
        return m;
    }

    private static Map<Integer, String> defaultExperimentNames() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(1, "第1次作业");
        m.put(2, "第1次实验");
        m.put(3, "第2次作业(单链表)");
        m.put(4, "第2次实验(单链表)");
        m.put(5, "第3次作业(单链表)");
        m.put(6, "第3次实验(链表应用)");
        m.put(7, "第4次作业(双向循环链表)");
        m.put(8, "第4次实验(栈)");
        m.put(9, "第5次实验(队列)");
        m.put(10, "第6次作业(BST)");
        m.put(11, "第6次实验(二叉树遍历)");
        m.put(12, "第7次实验(Huffman)");
        m.put(13, "第8次实验(HashTable)");
        m.put(14, "第9次实验(DFS/BFS)");
        m.put(15, "第10次实验(栈应用)");
        m.put(16, "第11次实验(Dijkstra/Prim)");
        m.put(17, "第12次实验");
        m.put(18, "期中复习");
        m.put(19, "例题");
        return m;
    }
}
