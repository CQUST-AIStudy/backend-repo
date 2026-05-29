package com.tap.backend.translation;

import java.util.concurrent.atomic.AtomicLong;

public final class DeepLRateLimiter {
  private final long minIntervalMs;
  private final AtomicLong last = new AtomicLong(0);

  public DeepLRateLimiter(long minIntervalMs) {
    this.minIntervalMs = Math.max(0, minIntervalMs);
  }

  public void acquire() {
    if (minIntervalMs <= 0) return;
    while (true) {
      long now = System.currentTimeMillis();
      long prev = last.get();
      long nextAllowed = prev + minIntervalMs;
      if (now >= nextAllowed) {
        if (last.compareAndSet(prev, now)) return;
        continue;
      }
      try {
        Thread.sleep(Math.min(200, nextAllowed - now));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
