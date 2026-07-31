package com.tap.backend.api.student;

import com.tap.backend.security.StudentPrincipalResolver;
import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.grading.PtaGradingService;
import com.tap.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端只读：查看自己已发布的 PTA 批改结果（分数 + 教师评语）。
 * 当前学生从登录会话解析，不接受前端传入学生标识。
 */
@RestController
@RequestMapping("/api/student/pta-grading")
public class StudentPtaGradingController {

    private final PtaGradingService service;
    private final StudentPrincipalResolver studentPrincipalResolver;

    public StudentPtaGradingController(PtaGradingService service,
                                       StudentPrincipalResolver studentPrincipalResolver) {
        this.service = service;
        this.studentPrincipalResolver = studentPrincipalResolver;
    }

    /** 按 offering 查看本人已发布 PTA 批改结果；无则 {published:false}。 */
    @GetMapping
    public ResponseEntity<?> result(@RequestParam("offeringId") Long offeringId,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        StudentPrincipalResolver.ResolvedStudent student = studentPrincipalResolver.requireStudent(principal);
        return ResponseEntity.ok(ApiResponse.of(
                service.getPublishedForStudent(offeringId, student.studentNum())));
    }
}
