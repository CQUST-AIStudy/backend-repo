package com.tap.backend.service;

import com.tap.backend.domain.classroom.TeachingClassEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;

@Component
public class PtaSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PtaSyncScheduler.class);

    private final PtaSyncService syncService;

    @Value("${pta.scheduler.max-concurrency:3}")
    private int maxConcurrency;

    @Value("${pta.scheduler.submit-interval-ms:200}")
    private long submitIntervalMs;

    public PtaSyncScheduler(PtaSyncService syncService) {
        this.syncService = syncService;
    }

    /** Daily sync at 02:00 with bounded parallelism. */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledSync() {
        List<TeachingClassEntity> classes = syncService.listSyncEnabledClasses();
        if (classes.isEmpty()) {
            log.info("[PTA定时同步] 没有开启同步的班级");
            return;
        }

        int workerCount = Math.max(1, Math.min(maxConcurrency, classes.size()));
        log.info("[PTA定时同步] 开始处理 {} 个班级，最大并发 {}", classes.size(), workerCount);

        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CompletionService<String> completionService = new ExecutorCompletionService<>(pool);

        try {
            int submitted = 0;
            for (TeachingClassEntity tc : classes) {
                completionService.submit(() -> {
                    try {
                        syncService.triggerSyncScheduled(tc.getId());
                        return "SUCCESS: " + tc.getName() + " (id=" + tc.getId() + ")";
                    } catch (Exception e) {
                        log.warn("[PTA定时同步] 班级 {} 同步失败: {}", tc.getName(), e.getMessage());
                        return "FAILED: " + tc.getName() + " (id=" + tc.getId() + ")";
                    }
                });
                submitted++;
                if (submitIntervalMs > 0) {
                    Thread.sleep(submitIntervalMs);
                }
            }

            int success = 0;
            int failed = 0;
            for (int i = 0; i < submitted; i++) {
                try {
                    String result = completionService.take().get();
                    if (result.startsWith("SUCCESS")) {
                        success++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                }
            }
            log.info("[PTA定时同步] 完成。成功 {}，失败 {}", success, failed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[PTA定时同步] 任务被中断");
        } finally {
            pool.shutdownNow();
        }
    }
}
