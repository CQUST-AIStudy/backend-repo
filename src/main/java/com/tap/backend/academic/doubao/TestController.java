package com.tap.backend.academic.doubao;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TestController {

    // 简单的GET测试端点
    @GetMapping("/test")
    public Map<String, String> test() {
        Map<String, String> response = new HashMap<>();
        response.put("message", "API测试成功");
        response.put("status", "ok");
        return response;
    }

    // 简单的POST测试端点，不使用请求体
    @PostMapping("/simple-chat")
    public Map<String, String> simpleChat() {
        Map<String, String> response = new HashMap<>();
        response.put("reply", "这是一个测试回复，不需要请求参数");
        return response;
    }

    // 接收简单文本的POST测试端点
    @PostMapping("/echo")
    public String echo(@RequestBody String message) {
        return "你发送的消息是: " + message;
    }
}