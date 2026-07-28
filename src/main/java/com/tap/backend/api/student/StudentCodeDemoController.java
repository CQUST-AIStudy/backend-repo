package com.tap.backend.api.student;

import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.animation.StudentCodeDemoService;
import com.tap.common.api.ApiResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端「每题代码执行演示」接口。
 * <p>
 * 当前学生从登录会话解析，不接受前端传入学生标识；只演示学生本人已入库的代码。
 */
@RestController
@RequestMapping("/api/student/code-demo")
public class StudentCodeDemoController {

    private final StudentCodeDemoService codeDemoService;

    public StudentCodeDemoController(StudentCodeDemoService codeDemoService) {
        this.codeDemoService = codeDemoService;
    }

    /** 读缓存：无则返回 {@code {status:'NONE'}}。 */
    @GetMapping
    public ResponseEntity<?> getCached(
            @RequestParam("experimentId") Long experimentId,
            @RequestParam("problemNo") String problemNo,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Map<String, Object> result = codeDemoService.getCached(experimentId, problemNo, principal);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /** 生成 / 重新生成。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestBody GenerateRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Map<String, Object> result = codeDemoService.generate(
                request.experimentId(),
                request.problemNo(),
                request.stdin(),
                request.force() != null && request.force(),
                principal);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    public record GenerateRequest(Long experimentId, String problemNo, String stdin, Boolean force) {}
}
