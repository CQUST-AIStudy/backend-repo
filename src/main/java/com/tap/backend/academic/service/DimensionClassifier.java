package com.tap.backend.academic.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把实验(题目集/作业)归入一个稳定的能力维度。
 *
 * <p>旧的 {@code SkillTreeConfig} 把维度硬编码到遗留 experiment_id(1-19)，真实教学班的实验
 * id 来自 assignment_offering，无法对上。本分类器改为按"实验名 + 题目知识点"的文本特征
 * 动态归类，维度词汇保持稳定(线性表/栈与队列/树/图/哈希/综合)，便于跨班级横向比较。</p>
 *
 * <p>纯静态、无副作用，便于单测覆盖。</p>
 */
public final class DimensionClassifier {

    public static final String FALLBACK_DIMENSION = "综合";
    /** 无法分类的占位维度，用于质量统计，不会作为正式维度输出。 */
    public static final String UNCLASSIFIED = "未分类";

    /**
     * 有序规则。注意：图相关的复合词(最小生成树/Kruskal/Prim/Dijkstra/拓扑)必须排在"树"之前，
     * 否则"最小生成树"会被"树"规则误吞。英文用更完整的词(linkedlist/dijkstra)而非裸 list/map，
     * 避免"Adjacency List Graph"先命中线性表。
     */
    private static final List<Rule> RULES = List.of(
            // 图复合词优先：含"树/图"字样但本质是图论
            rule("(?i).*(最小生成树|kruskal|prim算法|dijkstra|迪杰斯特拉|拓扑排序|拓扑|最短路|最短路径|网络流|并查集).*", "图"),
            rule("(?i).*(\\bdfs\\b|\\bbfs\\b|深度优先|广度优先|graph|图论|连通图).*", "图"),
            rule("(?i).*(图|graph).*", "图"),

            rule("(?i).*(链表|线性表|线性结构|线性存储|顺序存储|链式存储|顺序表|数组|arraylist|linkedlist|linked\\s+list|列表|向量|vector).*", "线性表"),
            rule("(?i).*(栈|队列|stack|queue|单调栈|单调队列|双端队列|deque).*", "栈与队列"),
            rule("(?i).*(二叉|哈夫曼|huffman|\\bbst\\b|\\bavl\\b|平衡树|trie|字典树|tree|树).*", "树"),
            rule("(?i).*(哈希|hash|散列).*", "哈希")
    );

    private DimensionClassifier() {
    }

    /**
     * 对一份文本(实验名 + 题目知识点拼接)做单维度归类，取首条命中。
     * 仅用于无多知识点场景；offering 级归类请用 {@link #classifyOffering}。
     */
    public static String classify(CharSequence... texts) {
        String joined = join(texts);
        for (Rule rule : RULES) {
            if (rule.pattern.matcher(joined).matches()) {
                return rule.dimension;
            }
        }
        return FALLBACK_DIMENSION;
    }

    /**
     * offering 级归类：把多个题目知识点分别归类，取多数/单一结果。
     * <ul>
     *   <li>知识点 CSV 解析后逐个 classify，收集非 fallback 的不同维度集合</li>
     *   <li>恰好 1 个不同维度 → 该维度</li>
     *   <li>>1 个不同维度 → {@link #FALLBACK_DIMENSION}（综合实验）</li>
     *   <li>0 个有效（知识点缺失或全未分类）→ 回退到实验名 classify；仍无命中 → {@link #UNCLASSIFIED}</li>
     * </ul>
     *
     * @param experimentName  实验/作业名
     * @param knowledgeLeafCsv 以 ;,，、 分隔的叶子知识点
     */
    public static String classifyOffering(String experimentName, String knowledgeLeafCsv) {
        Set<String> distinctDims = new LinkedHashSet<>();
        if (knowledgeLeafCsv != null && !knowledgeLeafCsv.isBlank()) {
            for (String leaf : knowledgeLeafCsv.split("[;,，、|/]+")) {
                String dim = classify(leaf);
                if (!dim.equals(FALLBACK_DIMENSION)) {
                    distinctDims.add(dim);
                }
            }
        }
        if (distinctDims.size() == 1) {
            return distinctDims.iterator().next();
        }
        if (distinctDims.size() > 1) {
            return FALLBACK_DIMENSION;
        }
        // 知识点缺失：用实验名兜底
        String byName = classify(experimentName);
        return byName.equals(FALLBACK_DIMENSION) ? UNCLASSIFIED : byName;
    }

    /**
     * 按 knowledge_path 真实层级提取维度（多课程自适应，替代固定词表作为主归类方式）。
     * <p>优先用知识点路径的章节级作为维度：数据结构课出"线性表/树/图"，C 语言课出"指针/函数"，
     * 不再受限于固定词表。同一 offering 多个知识点时取出现频次最高的章节（众数），避免多维被
     * 强制并成"综合"。path 缺失或全部解析失败时回退到 {@link #classifyOffering} 固定词表。
     *
     * @param knowledgePathCsv 知识点完整路径，多个以 ; 分隔（pta_problem_detail.knowledge_path）
     * @param knowledgeLeafCsv 叶子知识点，多个以 ; 分隔（回退用）
     * @param experimentName   实验/作业名（最终兜底）
     */
    public static String classifyByPath(String knowledgePathCsv, String knowledgeLeafCsv, String experimentName) {
        if (knowledgePathCsv != null && !knowledgePathCsv.isBlank()) {
            Map<String, Integer> dimCounts = new LinkedHashMap<>();
            for (String path : knowledgePathCsv.split("[;,，；|、]+")) {
                String dim = chapterOfPath(path);
                if (dim != null && !dim.isBlank()) {
                    dimCounts.merge(dim, 1, Integer::sum);
                }
            }
            if (!dimCounts.isEmpty()) {
                String best = null;
                int bestCnt = 0;
                for (var e : dimCounts.entrySet()) {
                    if (e.getValue() > bestCnt) {
                        best = e.getKey();
                        bestCnt = e.getValue();
                    }
                }
                return best;
            }
        }
        return classifyOffering(experimentName, knowledgeLeafCsv);
    }

    /**
     * 从知识点路径提取章节级维度：路径 ≥3 级取倒数第二级（叶子的父 = 章节），
     * ≤2 级取最后一级。层级分隔符兼容 / » > 》 等。
     */
    private static String chapterOfPath(String path) {
        if (path == null) return null;
        String trimmed = path.trim();
        if (trimmed.isEmpty()) return null;
        String[] raw = trimmed.split("[/»>》]+");
        List<String> levels = new ArrayList<>();
        for (String p : raw) {
            String s = p.trim();
            if (!s.isEmpty()) levels.add(s);
        }
        if (levels.isEmpty()) return null;
        int idx = levels.size() >= 3 ? levels.size() - 2 : levels.size() - 1;
        return levels.get(idx);
    }

    private static String join(CharSequence[] texts) {
        if (texts == null || texts.length == 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        for (CharSequence t : texts) {
            if (t != null && t.length() > 0) {
                buf.append(' ').append(t);
            }
        }
        return buf.toString().toLowerCase(Locale.ROOT);
    }

    private static Rule rule(String regex, String dimension) {
        return new Rule(Pattern.compile(regex, Pattern.UNICODE_CASE), dimension);
    }

    private record Rule(Pattern pattern, String dimension) {
    }
}
