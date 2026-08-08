package com.tap.backend.api.grading;

import com.tap.backend.security.UserPrincipal;
import com.tap.backend.service.grading.PtaGradingService;
import com.tap.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端 PTA 批改接口：按 offering(题集) 预览客观分、批量生成评语、查看、发布给学生。
 */
@RestController
@RequestMapping("/api/grading/pta")
public class PtaGradingController {

    private final PtaGradingService service;

    public PtaGradingController(PtaGradingService service) {
        this.service = service;
    }

    public record OfferingRequest(Long offeringId, Boolean force) {}

    public record StudentDetailRequest(Long offeringId, Long studentId) {}

    /** 预览：客观分 + 学生列表（不调 AI、不落库）。 */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody OfferingRequest req, @AuthenticationPrincipal UserPrincipal p) {
        return ResponseEntity.ok(ApiResponse.of(service.preview(req.offeringId(), p)));
    }

    /** 批量生成 AI 评语并落库。 */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody OfferingRequest req, @AuthenticationPrincipal UserPrincipal p) {
        boolean force = req.force() != null && req.force();
        return ResponseEntity.ok(ApiResponse.of(service.generate(req.offeringId(), force, p)));
    }

    /** 列出该 offering 已保存的批改结果。 */
    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam("offeringId") Long offeringId, @AuthenticationPrincipal UserPrincipal p) {
        List<Map<String, Object>> items = service.list(offeringId, p);
        return ResponseEntity.ok(ApiResponse.of(Map.of("items", items)));
    }

    /** 单条批改详情（含每题明细）。 */
    @GetMapping("/detail/{id}")
    public ResponseEntity<?> detail(@PathVariable("id") Long id, @AuthenticationPrincipal UserPrincipal p) {
        return ResponseEntity.ok(ApiResponse.of(service.detail(id, p)));
    }

    /** 按学生详情：每题 PTA 判题状态 + 学生代码 + 题面（实时聚合，未生成评语也可看）。 */
    @PostMapping("/student-detail")
    public ResponseEntity<?> studentDetail(@RequestBody StudentDetailRequest req, @AuthenticationPrincipal UserPrincipal p) {
        return ResponseEntity.ok(ApiResponse.of(service.studentDetail(req.offeringId(), req.studentId(), p)));
    }

    /** 发布该 offering 的批改结果给学生。 */
    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestBody OfferingRequest req, @AuthenticationPrincipal UserPrincipal p) {
        return ResponseEntity.ok(ApiResponse.of(service.publish(req.offeringId(), p)));
    }
}
