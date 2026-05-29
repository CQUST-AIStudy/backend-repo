package com.tap.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * IP 级限流过滤器 — 防止单 IP 刷接口
 * 全局: 100 req/s per IP
 * AI 端点: 10 req/min per IP
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    /** 全局限流: 每秒最多 100 次 */
    private final LoadingCache<String, AtomicInteger> globalBucket =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(1))
            .build(k -> new AtomicInteger(0));

    /** AI 端点限流: 每分钟最多 10 次 */
    private final LoadingCache<String, AtomicInteger> aiBucket =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build(k -> new AtomicInteger(0));

    private static final int GLOBAL_LIMIT = 100;
    private static final int AI_LIMIT = 10;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String ip = getClientIp(request);
        String path = request.getRequestURI();

        // 全局限流
        int globalCount = globalBucket.get(ip).incrementAndGet();
        if (globalCount > GLOBAL_LIMIT) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\",\"code\":429}");
            return;
        }

        // AI 端点限流
        if (isAiEndpoint(path)) {
            int aiCount = aiBucket.get(ip).incrementAndGet();
            if (aiCount > AI_LIMIT) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"AI接口调用过于频繁，每分钟最多10次\",\"code\":429}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAiEndpoint(String path) {
        return path.startsWith("/api/tap-chat")
            || path.startsWith("/api/rag/chat")
            || path.contains("/ai-feedback")
            || path.contains("/summarize");
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
