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
    private Map<String, List<Integer>> dimensions = new LinkedHashMap<>();

    /** Dimension name → human-readable description */
    private Map<String, String> descriptions = defaultDescriptions();

    /** Experiment ID → display name */
    private Map<Integer, String> experimentNames = new LinkedHashMap<>();

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

}
