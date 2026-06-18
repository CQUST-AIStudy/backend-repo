package com.tap.backend.service.grading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tap.backend.domain.grading.GradingAgentConfigEntity;
import com.tap.backend.dto.grading.AgentConfigDto;
import com.tap.backend.repository.grading.GradingAgentConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

@Service
public class GradingAgentConfigService {
    private static final Logger log = LoggerFactory.getLogger(GradingAgentConfigService.class);

    private final GradingAgentConfigRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public GradingAgentConfigService(GradingAgentConfigRepository repository,
                                     StringRedisTemplate redis,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    private static String redisKey(String code) { return "agent:config:" + code; }

    @Transactional(readOnly = true)
    public Optional<AgentConfigDto> findByCode(String code) {
        String key = redisKey(code);
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            try {
                return Optional.of(objectMapper.readValue(cached, AgentConfigDto.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse cached agent config {}, evicting", code, e);
                redis.delete(key);
            }
        }
        return repository.findByCode(code).map(this::toDto).map(dto -> {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(dto), Duration.ofMinutes(30));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize agent config {}", code, e);
            }
            return dto;
        });
    }

    @Transactional
    public AgentConfigDto update(String code, AgentConfigDto dto) {
        GradingAgentConfigEntity entity = repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Agent config not found: " + code));
        entity.setName(dto.name());
        entity.setPromptTemplate(dto.promptTemplate());
        entity.setModel(dto.model());
        entity.setTemperature(dto.temperature());
        entity.setMaxTokens(dto.maxTokens());
        entity.setEnabled(dto.enabled());
        repository.save(entity);
        redis.delete(redisKey(code));
        return findByCode(code).orElseThrow();
    }

    private AgentConfigDto toDto(GradingAgentConfigEntity e) {
        return new AgentConfigDto(
                e.getId(),
                e.getCode(),
                e.getName(),
                e.getPromptTemplate(),
                e.getModel(),
                e.getTemperature() != null ? e.getTemperature() : new BigDecimal("0.30"),
                e.getMaxTokens(),
                e.isEnabled()
        );
    }
}
