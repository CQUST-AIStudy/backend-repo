package com.tap.backend.academic.doubao;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import io.reactivex.Flowable;
import io.reactivex.subscribers.DisposableSubscriber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// @RestController  // 已替换为 DeepSeekChatController
// @RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:8080", "http://127.0.0.1:8080","http://47.108.176.134:8090"},
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
        allowCredentials = "true")
public class DouBaoAssistantController {

    @Value("${volcano.api.key}")
    private String apiKey;

    @Value("${volcano.api.model}")
    private String modelName;

    private static final Gson gson = new Gson();

    // 用于识别"思考"部分内容的正则表达式
    private static final Pattern THINK_PATTERN = Pattern.compile(
            "(?i)(好的，|好,|首先，|嗯，|让我思考一下|我是一个|作为一个|我需要|我应该).*(我需要|我会|我应该|我可以).*?((?=你好)|(?=我的回答)|$)",
            Pattern.DOTALL
    );

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody Map<String, String> request) {
        // 正确提取用户输入
        String userInput = request.get("userInput");
        System.out.println("收到请求，用户输入: " + userInput);

        if (userInput == null || userInput.trim().isEmpty()) {
            return ResponseEntity.ok().body(outputStream -> {
                outputStream.write("请输入有效的问题".getBytes());
                outputStream.flush();
            });
        }

        // 构建流式响应
        StreamingResponseBody responseBody = outputStream -> {
            StringBuilder responseBuffer = new StringBuilder();
            AtomicBoolean isCancelled = new AtomicBoolean(false);

            try {
                // 创建火山引擎API服务
                ArkService arkService = ArkService.builder().apiKey(apiKey).build();

                // 设置系统提示
                String systemPrompt = "你是一个学习助手，专注于帮助学生解决学习问题，特别是与数据结构、算法和编程相关的问题。请直接回答问题，不要先分析自己的角色或思考过程。回答要有针对性且客观。";

                // 构建消息列表
                List<ChatMessage> messages = new ArrayList<>();
                messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.SYSTEM)
                        .content(systemPrompt)
                        .build());
                messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.USER)
                        .content(userInput)
                        .build());

                // 设置请求参数
                ChatCompletionRequest completionRequest = ChatCompletionRequest.builder()
                        .model(modelName)
                        .messages(messages)
                        .stream(true)  // 启用流式输出
                        .build();

                // 发送SSE事件前缀
                try {
                    outputStream.write("data: ".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException e) {
                    System.err.println("发送SSE前缀时客户端断开连接: " + e.getMessage());
                    isCancelled.set(true);
                    return;
                }

                // 创建缓冲区减少频繁I/O
                StringBuilder chunkBuffer = new StringBuilder();
                final int[] flushCount = {0};
                final int FLUSH_THRESHOLD = 5; // 每积累5个块或50个字符才刷新

                // 处理流式响应
                Flowable<ChatCompletionChunk> flowable = arkService.streamChatCompletion(completionRequest);

                DisposableSubscriber<ChatCompletionChunk> subscriber = new DisposableSubscriber<ChatCompletionChunk>() {
                    @Override
                    public void onNext(ChatCompletionChunk chunk) {
                        if (isCancelled.get()) {
                            cancel();
                            return;
                        }

                        try {
                            ChatMessage chatMessage = chunk.getChoices().get(0).getMessage();
                            if (chatMessage != null && chatMessage.getContent() != null) {
                                String content = (String) chatMessage.getContent();

                                // 添加到响应缓冲区
                                responseBuffer.append(content);

                                // 添加到临时缓冲区
                                chunkBuffer.append(content);
                                flushCount[0]++;

                                // 达到阈值时才输出，减少I/O操作
                                if (flushCount[0] >= FLUSH_THRESHOLD || chunkBuffer.length() > 50) {
                                    try {
                                        outputStream.write(chunkBuffer.toString().getBytes(StandardCharsets.UTF_8));
                                        outputStream.flush();
                                        chunkBuffer.setLength(0); // 清空缓冲区
                                        flushCount[0] = 0;
                                    } catch (IOException e) {
                                        System.err.println("写入数据时检测到客户端断开: " + e.getMessage());
                                        isCancelled.set(true);
                                        cancel();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("处理流式响应时出错: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.err.println("流式请求失败: " + t.getMessage());
                        t.printStackTrace();
                        try {
                            if (!isCancelled.get()) {
                                String errorMessage = "抱歉，AI助手暂时无法回答您的问题，请稍后再试。";
                                outputStream.write(errorMessage.getBytes(StandardCharsets.UTF_8));
                                outputStream.flush();
                            }
                        } catch (IOException e) {
                            System.err.println("发送错误消息时出错: " + e.getMessage());
                        } finally {
                            arkService.shutdownExecutor();
                        }
                    }

                    @Override
                    public void onComplete() {
                        System.out.println("流式请求完成");
                        try {
                            // 发送剩余缓冲区内容
                            if (chunkBuffer.length() > 0) {
                                try {
                                    outputStream.write(chunkBuffer.toString().getBytes(StandardCharsets.UTF_8));
                                    outputStream.flush();
                                } catch (IOException e) {
                                    // 忽略最后刷新错误
                                }
                            }

                            // 模型输出完毕时处理完整响应 - 过滤 "think" 部分内容
                            String fullResponse = responseBuffer.toString();
                            String filteredResponse = removeThinkingPart(fullResponse);

                            // 仅当过滤后的内容与原始内容不同且不为空时，才写入过滤后的内容
                            if (!filteredResponse.equals(fullResponse) && !filteredResponse.isEmpty()) {
                                System.out.println("检测到思考内容，已过滤。过滤前字符数:" + fullResponse.length()
                                        + "，过滤后字符数:" + filteredResponse.length());

                                try {
                                    // 添加一个换行符，然后发送过滤后的内容
                                    outputStream.write(("\n\n" + filteredResponse).getBytes(StandardCharsets.UTF_8));
                                    outputStream.flush();
                                } catch (IOException e) {
                                    // 忽略最后刷新错误
                                }
                            }

                            System.out.println("模型生成完毕，总共生成字符: " + responseBuffer.length());
                        } finally {
                            arkService.shutdownExecutor();
                        }
                    }
                };

                flowable.subscribe(subscriber);

                // 等待订阅者完成或超时
                try {
                    synchronized (subscriber) {
                        subscriber.wait(60000); // 等待60秒或直到完成
                    }
                } catch (InterruptedException e) {
                    System.err.println("等待响应时被中断: " + e.getMessage());
                }

                // 如果未取消，强制取消
                if (!subscriber.isDisposed()) {
                    subscriber.dispose();
                }

            } catch (Exception e) {
                System.err.println("处理聊天请求时发生异常: " + e.getMessage());
                e.printStackTrace();
                if (!isCancelled.get()) {
                    try {
                        String errorMessage = "抱歉，处理您的请求时出现了问题，请稍后再试。";
                        outputStream.write(errorMessage.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (IOException ioException) {
                        // 忽略
                    }
                }
            }

            System.out.println("资源已清理，请求处理完成");
        };

        // 设置响应头，确保流式传输正常工作
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-store")
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .header("Pragma", "no-cache")
                .body(responseBody);
    }

    /**
     * 从响应中移除"思考"部分的内容
     * 识别模式:
     * 1. 以"好的"、"首先"、"我需要"等开头的内容
     * 2. 包含"我会"、"我应该"、"我可以"等自我指示的内容
     * 3. 直到遇到"你好"、"我的回答"等实际回答开始的内容
     */
    private String removeThinkingPart(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }

        // 应用正则表达式替换"思考"部分
        Matcher matcher = THINK_PATTERN.matcher(response);
        String filtered = matcher.replaceAll("");

        // 去除可能残留的前缀空白字符
        filtered = filtered.trim();

        // 如果过滤后字符串为空，返回原始响应
        if (filtered.isEmpty()) {
            return response;
        }

        return filtered;
    }

    // 保留辅助类，但我们不再直接使用它接收请求
    static class ChatRequest {
        private String userInput;

        public String getUserInput() {
            return userInput;
        }

        public void setUserInput(String userInput) {
            this.userInput = userInput;
        }

        @Override
        public String toString() {
            return "ChatRequest{userInput='" + userInput + "'}";
        }
    }
}