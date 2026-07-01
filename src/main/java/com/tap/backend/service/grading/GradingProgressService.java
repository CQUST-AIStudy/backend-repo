package com.tap.backend.service.grading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 维护每个批改任务的 SSE 订阅，并把 worker 推送的细粒度阶段进度与后端任务级状态
 * 实时转发给前端，替代原来的"整批份数占比 + 轮询"。
 *
 * <p>worker 通过 Redis 频道 {@code grading:progress} 发送单份提交的阶段事件，后端的
 * Redis 监听器调用 {@link #onProgressMessage(String)} 进行解析并按 taskId 广播。</p>
 */
@Service
public class GradingProgressService {

    private static final Logger log = LoggerFactory.getLogger(GradingProgressService.class);

    private final Map<Long, Set<SseEmitter>> emittersByTask = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public GradingProgressService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 前端订阅某个任务的进度流。 */
    public SseEmitter subscribe(Long taskId) {
        // 0 = 永不超时；连接的生命周期由客户端关闭或 onError 控制。
        SseEmitter emitter = new SseEmitter(0L);
        emittersByTask.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(taskId, emitter);
        });
        emitter.onError(e -> remove(taskId, emitter));
        try {
            emitter.send(SseEmitter.event().name("open").data("{\"ok\":true}"));
        } catch (IOException e) {
            remove(taskId, emitter);
        }
        return emitter;
    }

    /** 解析 worker 的进度消息并按 taskId 广播给所有订阅者。 */
    public void onProgressMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode taskIdNode = node.get("taskId");
            if (taskIdNode == null || taskIdNode.isNull()) {
                return;
            }
            broadcast(taskIdNode.asLong(), "progress", body);
        } catch (Exception e) {
            log.warn("Failed to handle grading progress message: {}", e.getMessage());
        }
    }

    /** 推送一个任务级快照（状态 + 计数），用于驱动前端整体进度条与状态切换。 */
    public void broadcastTaskSnapshot(Long taskId, String status, int totalCount,
                                      int completedCount, int failedCount) {
        if (taskId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "task_status");
        payload.put("taskId", taskId);
        payload.put("status", status);
        payload.put("totalCount", totalCount);
        payload.put("completedCount", completedCount);
        payload.put("failedCount", failedCount);
        try {
            broadcast(taskId, "task", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to serialize task snapshot for task {}: {}", taskId, e.getMessage());
        }
    }

    private void broadcast(Long taskId, String eventName, String jsonData) {
        Set<SseEmitter> emitters = emittersByTask.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(jsonData));
            } catch (Exception e) {
                remove(taskId, emitter);
            }
        }
    }

    private void remove(Long taskId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByTask.get(taskId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByTask.remove(taskId);
            }
        }
    }
}
