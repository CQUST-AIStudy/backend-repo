package com.tap.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine 本地缓存 — 减少重复计算和数据库查询
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
            buildCache("classProfile", Duration.ofMinutes(5), 10),
            buildCache("experimentList", Duration.ofMinutes(2), 20),
            buildCache("skillTree", Duration.ofMinutes(30), 5)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, Duration ttl, int maxSize) {
        return new CaffeineCache(name,
            Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
