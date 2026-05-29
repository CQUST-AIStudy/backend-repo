package com.tap.backend.quota;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaService {
  private final QuotaProperties props;
  private final StringRedisTemplate redis;
  private final UserDailyQuotaUsageRepository usageRepository;
  private final UserRepository userRepository;

  public QuotaService(QuotaProperties props, StringRedisTemplate redis,
      UserDailyQuotaUsageRepository usageRepository, UserRepository userRepository) {
    this.props = props;
    this.redis = redis;
    this.usageRepository = usageRepository;
    this.userRepository = userRepository;
  }

  @Transactional
  public void consumeTranslationChars(long userId, long chars) {
    if (chars <= 0) return;
    UserEntity user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    if (props.adminUnlimited() && user.getRole() == UserRole.ADMIN) return;
    long limit = props.translationCharsPerDay();
    if (limit <= 0) return;

    LocalDate day = LocalDate.now(ZoneOffset.UTC);
    String key = "tap:quota:tr:" + userId + ":" + day;
    Long used = redis.opsForValue().increment(key, chars);
    redis.expire(key, Duration.ofDays(2));
    usageRepository.upsertAdd(userId, day, chars, 0);
    if (used != null && used > limit) {
      throw new QuotaExceededException("translation daily quota exceeded");
    }
  }

  @Transactional
  public void consumeAiRequests(long userId, long requests) {
    if (requests <= 0) return;
    UserEntity user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    if (props.adminUnlimited() && user.getRole() == UserRole.ADMIN) return;
    long limit = props.aiRequestsPerDay();
    if (limit <= 0) return;

    LocalDate day = LocalDate.now(ZoneOffset.UTC);
    String key = "tap:quota:ai:" + userId + ":" + day;
    Long used = redis.opsForValue().increment(key, requests);
    redis.expire(key, Duration.ofDays(2));
    usageRepository.upsertAdd(userId, day, 0, requests);
    if (used != null && used > limit) {
      throw new QuotaExceededException("ai requests daily quota exceeded");
    }
  }
}
