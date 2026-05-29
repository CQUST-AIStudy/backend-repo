package com.tap.backend.quota;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserDailyQuotaUsageRepository extends JpaRepository<UserDailyQuotaUsageEntity, Long> {
  java.util.List<UserDailyQuotaUsageEntity> findAllByUsageDate(LocalDate usageDate);

  @Modifying
  @Query(value = """
      insert into user_daily_quota_usage(user_id, usage_date, translation_chars, ai_requests, updated_at)
      values (:userId, :usageDate, :trChars, :aiReq, now())
      on duplicate key update
        translation_chars = translation_chars + values(translation_chars),
        ai_requests = ai_requests + values(ai_requests),
        updated_at = now()
      """, nativeQuery = true)
  void upsertAdd(@Param("userId") long userId,
      @Param("usageDate") LocalDate usageDate,
      @Param("trChars") long trChars,
      @Param("aiReq") long aiReq);
}
