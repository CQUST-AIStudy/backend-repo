package com.tap.backend.api.student;

import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.animation.CodePlaygroundService;
import com.tap.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助教「代码演示（手动输入）」接口。
 * <p>
 * 学生手动粘贴代码 + 题目 + stdin，生成执行/错误动画并按学号保存历史（可回看/删除）。
 * 当前学生从登录会话解析，历史按 {@code student_no} 归属校验。
 */
@RestController
@RequestMapping("/api/student/ai-assistant/code-demo")
public class StudentCodePlaygroundController {

    private final CodePlaygroundService service;

    public StudentCodePlaygroundController(CodePlaygroundService service) {
        this.service = service;
    }

    public record GenerateRequest(String title, String problemMd, String code, String stdin) {}

    /** 生成并保存一次演示。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestBody GenerateRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Map<String, Object> result = service.generate(
                request.title(), request.problemMd(), request.code(), request.stdin(), principal);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** 本人历史列表（最多 50 条，按时间倒序）。 */
    @GetMapping("/history")
    public ResponseEntity<?> history(@AuthenticationPrincipal UserPrincipal principal) {
        List<Map<String, Object>> items = service.history(principal);
        return ResponseEntity.ok(ApiResponse.of(Map.of("items", items)));
    }

    /** 单条历史详情（含 demonstration）。 */
    @GetMapping("/history/{id}")
    public ResponseEntity<?> detail(@PathVariable("id") Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.of(service.detail(id, principal)));
    }

    /** 删除本人一条历史。 */
    @DeleteMapping("/history/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id, @AuthenticationPrincipal UserPrincipal principal) {
        service.delete(id, principal);
        return ResponseEntity.ok(ApiResponse.of(Map.of("deleted", true)));
    }
}
