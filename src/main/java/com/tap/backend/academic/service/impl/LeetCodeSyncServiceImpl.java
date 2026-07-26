package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.LeetCodeProblemDao;
import com.tap.backend.academic.dao.LeetCodeProblemTagDao;
import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.entity.LeetCodeProblemTag;
import com.tap.backend.academic.service.LeetCodeSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * LeetCode数据同步服务实现
 */
@Service
public class LeetCodeSyncServiceImpl implements LeetCodeSyncService {

    private static final Logger logger = LoggerFactory.getLogger(LeetCodeSyncServiceImpl.class);

    @Autowired
    private LeetCodeProblemDao leetCodeProblemDao;

    @Autowired
    private LeetCodeProblemTagDao leetCodeProblemTagDao;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 标签关键词映射
    private static final Map<String, String> TAG_KEYWORDS = new LinkedHashMap<>();
    static {
        // 数据结构标签
        TAG_KEYWORDS.put("数组", "数组|Array|array");
        TAG_KEYWORDS.put("链表", "链表|LinkedList|linked.*list");
        TAG_KEYWORDS.put("栈", "栈|Stack|stack");
        TAG_KEYWORDS.put("队列", "队列|Queue|queue");
        TAG_KEYWORDS.put("树", "树|Tree|tree|二叉树");
        TAG_KEYWORDS.put("堆", "堆|Heap|heap|优先队列");
        TAG_KEYWORDS.put("哈希表", "哈希|Hash|hash|散列");
        TAG_KEYWORDS.put("字符串", "字符串|String|string");
        
        // 算法标签
        TAG_KEYWORDS.put("排序", "排序|Sort|sort");
        TAG_KEYWORDS.put("二分查找", "二分|Binary.*Search|binary.*search");
        TAG_KEYWORDS.put("深度优先搜索", "深度优先|DFS|dfs|递归");
        TAG_KEYWORDS.put("广度优先搜索", "广度优先|BFS|bfs");
        TAG_KEYWORDS.put("回溯", "回溯|Backtrack|backtrack");
        TAG_KEYWORDS.put("贪心", "贪心|Greedy|greedy");
        TAG_KEYWORDS.put("分治", "分治|Divide.*Conquer|divide.*conquer");
        
        // 技巧标签
        TAG_KEYWORDS.put("双指针", "双指针|Two.*Pointer|two.*pointer");
        TAG_KEYWORDS.put("滑动窗口", "滑动窗口|Sliding.*Window|sliding.*window");
        TAG_KEYWORDS.put("动态规划", "动态规划|Dynamic.*Programming|dp|DP");
        TAG_KEYWORDS.put("位运算", "位运算|Bit.*Manipulation|bit.*manipulation");
        TAG_KEYWORDS.put("数学", "数学|Math|math");
        TAG_KEYWORDS.put("模拟", "模拟|Simulation|simulation");
    }

    @Override
    @Transactional
    public int syncProblemsFromJson(String jsonFilePath) {
        try {
            logger.info("开始从JSON文件同步LeetCode题目: {}", jsonFilePath);

            File jsonFile = resolveJsonFile(jsonFilePath);
            if (jsonFile == null || !jsonFile.exists()) {
                logger.error("JSON文件不存在，原始路径: {}", jsonFilePath);
                return 0;
            }
            logger.info("使用数据文件: {}", jsonFile.getAbsolutePath());

            JsonNode rootNode = objectMapper.readTree(jsonFile);
            if (!rootNode.isArray()) {
                logger.error("JSON文件格式错误，应该是数组格式");
                return 0;
            }

            int syncCount = 0;
            int totalCount = rootNode.size();
            
            for (int i = 0; i < totalCount; i++) {
                JsonNode problemNode = rootNode.get(i);
                if (syncSingleProblem(problemNode)) {
                    syncCount++;
                }
                
                if ((i + 1) % 50 == 0) {
                    logger.info("已处理 {}/{} 个题目", i + 1, totalCount);
                }
            }

            logger.info("同步完成，成功同步 {} 个题目", syncCount);
            return syncCount;
            
        } catch (Exception e) {
            logger.error("同步LeetCode题目失败", e);
            throw new RuntimeException("同步失败: " + e.getMessage(), e);
        }
    }
    @Override
    @Transactional
    public boolean syncSingleProblem(Object problemData) {
        try {
            JsonNode problemNode = (JsonNode) problemData;
            
            // 解析题目数据
            String input = problemNode.get("input").asText();
            String output = problemNode.get("output").asText();
            
            // 提取题目信息
            String title = extractTitle(input);
            String problemCode = extractProblemCode(title);
            Integer numericId = extractNumericId(problemCode);
            String difficulty = extractDifficulty(input);
            
            // 创建题目对象
            LeetCodeProblem problem = new LeetCodeProblem();
            problem.setSourceKey(generateSourceKey(title, problemCode));
            problem.setProblemCode(problemCode);
            problem.setNumericId(numericId);
            problem.setTitleMain(title);
            problem.setProblemText(input);
            problem.setSolutionText(output);
            problem.setDifficulty(difficulty);
            problem.setEstimatedMinutes(30); // 默认30分钟
            problem.setQualityScore(new BigDecimal("0.8000")); // 默认质量分数

            // 保存题目
            leetCodeProblemDao.insertOrUpdate(problem);
            if (problem.getId() != null) {
                // 提取并保存标签
                extractAndSaveTags(problem.getId(), input, output);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            logger.error("同步单个题目失败", e);
            throw new IllegalStateException("同步单个题目失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int extractAndSaveTags(Long problemId, String problemText, String solutionText) {
        try {
            Set<String> extractedTags = new LinkedHashSet<>();
            String combinedText = ((problemText == null ? "" : problemText) + " "
                    + (solutionText == null ? "" : solutionText)).toLowerCase();
            
            // 使用关键词匹配提取标签
            for (Map.Entry<String, String> entry : TAG_KEYWORDS.entrySet()) {
                String tagName = entry.getKey();
                String keywords = entry.getValue();
                
                if (Pattern.compile(keywords, Pattern.CASE_INSENSITIVE).matcher(combinedText).find()) {
                    extractedTags.add(tagName);
                }
            }
            
            // 如果没有提取到标签，添加默认标签
            if (extractedTags.isEmpty()) {
                extractedTags.add("模拟");
            }
            
            // 保存标签到数据库
            List<LeetCodeProblemTag> tags = new ArrayList<>();
            int index = 0;
            for (String tagName : extractedTags) {
                String tagCategory = getTagCategory(tagName);
                boolean isPrimary = index == 0; // 第一个标签设为主标签
                
                LeetCodeProblemTag tag = new LeetCodeProblemTag(problemId, tagCategory, tagName);
                tag.setPrimary(isPrimary);
                tag.setRelevanceScore(isPrimary ? new BigDecimal("0.9500") : new BigDecimal("0.8000"));
                tags.add(tag);
                index++;
            }
            
            // 批量插入标签
            if (!tags.isEmpty()) {
                leetCodeProblemTagDao.batchInsert(tags);
            }
            
            logger.debug("为题目 {} 提取并保存标签: {}", problemId, extractedTags);
            
            return extractedTags.size();
            
        } catch (Exception e) {
            logger.error("提取标签失败", e);
            throw new IllegalStateException("提取并保存标签失败: " + e.getMessage(), e);
        }
    }

    // 获取标签分类
    private String getTagCategory(String tagName) {
        if (Arrays.asList("数组", "链表", "栈", "队列", "树", "堆", "哈希表", "字符串").contains(tagName)) {
            return "data_structure";
        } else if (Arrays.asList("双指针", "滑动窗口", "动态规划", "位运算", "数学", "模拟").contains(tagName)) {
            return "technique";
        } else {
            return "algorithm";
        }
    }

    @Override
    public String getSyncStats() {
        try {
            int totalProblems = leetCodeProblemDao.count();
            return String.format("当前数据库中共有 %d 个LeetCode题目", totalProblems);
        } catch (Exception e) {
            logger.error("获取同步统计信息失败", e);
            return "获取统计信息失败";
        }
    }

    // 辅助方法
    private String extractTitle(String input) {
        // 从input中提取题目标题
        String[] lines = input.split("\n");
        for (String line : lines) {
            if (line.startsWith("题目：")) {
                return line.substring(3).trim();
            }
        }
        return "未知题目";
    }

    private String extractProblemCode(String title) {
        // 提取题目编号，如 "LCR 002"
        if (title.contains("LCR")) {
            int start = title.indexOf("LCR");
            int end = title.indexOf(" - ", start);
            if (end > start) {
                return title.substring(start, end).trim();
            }
        }
        return null;
    }

    private Integer extractNumericId(String problemCode) {
        if (problemCode != null && problemCode.contains(" ")) {
            try {
                String numPart = problemCode.split(" ")[1];
                return Integer.parseInt(numPart);
            } catch (Exception e) {
                // 忽略解析错误
            }
        }
        return null;
    }

    private String extractDifficulty(String input) {
        // 简单的难度判断逻辑
        String lowerInput = input.toLowerCase();
        if (lowerInput.contains("简单") || lowerInput.contains("easy")) {
            return "Easy";
        } else if (lowerInput.contains("困难") || lowerInput.contains("hard")) {
            return "Hard";
        } else if (lowerInput.contains("中等") || lowerInput.contains("medium")) {
            return "Medium";
        }
        return "Unknown";
    }

    private String generateSourceKey(String title, String problemCode) {
        if (problemCode != null) {
            return "code:" + problemCode;
        }
        return "title:" + title.hashCode();
    }

    private File resolveJsonFile(String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            return null;
        }

        Set<Path> candidates = new LinkedHashSet<>();
        Path given = Paths.get(inputPath);
        candidates.add(given);

        Path userDir = Paths.get(System.getProperty("user.dir", "."));
        candidates.add(userDir.resolve(inputPath));

        // 向上回溯目录，兼容从 AI_Ds 子目录启动后端的场景
        Path current = userDir;
        for (int i = 0; i < 5 && current != null; i++) {
            candidates.add(current.resolve(inputPath));
            candidates.add(current.resolve("datasets").resolve("leetcode").resolve("solutions_cleaned.json"));
            current = current.getParent();
        }

        for (Path candidate : candidates) {
            try {
                File file = candidate.normalize().toFile();
                if (file.exists() && file.isFile()) {
                    return file;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}
