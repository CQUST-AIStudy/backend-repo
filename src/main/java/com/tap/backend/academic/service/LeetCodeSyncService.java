package com.tap.backend.academic.service;

/**
 * LeetCode数据同步服务接口
 */
public interface LeetCodeSyncService {

    /**
     * 从清洗后的JSON文件同步题目到数据库
     * @param jsonFilePath JSON文件路径
     * @return 同步的题目数量
     */
    int syncProblemsFromJson(String jsonFilePath);

    /**
     * 同步单个题目
     * @param problemData 题目数据
     * @return 是否成功
     */
    boolean syncSingleProblem(Object problemData);

    /**
     * 提取题目标签
     * @param problemId 题目ID
     * @param problemText 题目内容
     * @param solutionText 题解内容
     * @return 提取的标签数量
     */
    int extractAndSaveTags(Long problemId, String problemText, String solutionText);

    /**
     * 获取同步统计信息
     * @return 统计信息
     */
    String getSyncStats();
}