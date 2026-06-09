package com.tap.backend.academic.controller;

import com.tap.backend.academic.entity.LeetCodeProblem;
import com.tap.backend.academic.service.LeetCodeProblemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import org.springframework.util.StringUtils;

/**
 * LeetCode 题库查询 Controller
 * 学生端通过此接口从数据库查询题目，不再直连爬虫服务。
 */
@RestController
@RequestMapping("/api/leetcode/problems")
public class LeetCodeProblemController {

    @Autowired
    private LeetCodeProblemService problemService;

    /**
     * 搜索题目（支持关键词 + 难度过滤 + 分页）
     * GET /api/leetcode/problems/search?keyword=xxx&difficulty=Medium&limit=20&offset=0
     */
    @GetMapping("/search")
    public ResponseEntity<java.util.Map<String, Object>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String difficulty,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        String diff = StringUtils.hasText(difficulty) ? difficulty.trim() : null;

        if (limit < 1) limit = 20;
        if (limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        java.util.List<LeetCodeProblem> items = problemService.search(kw, diff, offset, limit);
        int total = problemService.countBySearch(kw, diff);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", true);
        response.put("data", items);
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);
        return ResponseEntity.ok(response);
    }

    /**
     * 根据 ID 获取题目详情
     * GET /api/leetcode/problems/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<java.util.Map<String, Object>> getById(@PathVariable Long id) {
        LeetCodeProblem problem = problemService.findById(id);
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        if (problem == null) {
            response.put("success", false);
            response.put("message", "题目不存在");
            return ResponseEntity.ok(response);
        }
        response.put("success", true);
        response.put("data", problem);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取所有难度枚举值（供前端下拉框使用）
     * GET /api/leetcode/problems/meta/difficulties
     */
    @GetMapping("/meta/difficulties")
    public ResponseEntity<java.util.Map<String, Object>> getDifficulties() {
        java.util.List<String> diffs = java.util.Arrays.asList("Easy", "Medium", "Hard", "Unknown");
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", true);
        response.put("data", diffs);
        return ResponseEntity.ok(response);
    }
}
