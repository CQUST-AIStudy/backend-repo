package com.tap.backend.service.grading;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.GradingTaskStatus;
import com.tap.backend.domain.grading.SubmissionStatus;
import com.tap.backend.repo.GradingSubmissionRepository;
import com.tap.backend.repo.GradingTaskRepository;
import com.tap.backend.service.GradingSubmissionService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在任务全部评分完成后，异步批量生成每份提交的批注报告、错误演示动画以及任务级批次总评。
 * 只有当这些资源全部尝试生成完毕后，任务状态才会从 FINALIZING 变为 COMPLETED，
 * 从而保证教师看到“批改完成”时，所有可查看/可下载资源均已就绪。
 */
@Service
public class GradingFinalizeService {

    private static final Logger log = LoggerFactory.getLogger(GradingFinalizeService.class);

    private final GradingSubmissionService submissionService;
    private final GradingBatchReviewService batchReviewService;
    private final GradingTaskRepository taskRepo;
    private final GradingSubmissionRepository submissionRepo;
    private final Executor aiExecutor;
    private final GradingProgressService gradingProgressService;

    public GradingFinalizeService(GradingSubmissionService submissionService,
                                  GradingBatchReviewService batchReviewService,
                                  GradingTaskRepository taskRepo,
                                  GradingSubmissionRepository submissionRepo,
                                  @Qualifier("aiExecutor") Executor aiExecutor,
                                  GradingProgressService gradingProgressService) {
        this.submissionService = submissionService;
        this.batchReviewService = batchReviewService;
        this.taskRepo = taskRepo;
        this.submissionRepo = submissionRepo;
        this.aiExecutor = aiExecutor;
        this.gradingProgressService = gradingProgressService;
    }

    /**
     * 异步批量 finalize 一个任务下的所有提交资源，并生成批次总评。
     * 调用方应已将任务状态设为 {@link GradingTaskStatus#FINALIZING}。
     * 使用 fileExecutor 作为外层编排线程池，内部每个提交的资源生成再使用 aiExecutor，
     * 避免同一线程池同时阻塞等待导致的饥饿。
     */
    @Async("fileExecutor")
    public void finalizeTaskAsync(Long taskId, Long teacherId) {
        log.info("Starting async finalize for task {}", taskId);
        try {
            List<GradingSubmissionEntity> submissions = submissionRepo.findAllByTaskId(taskId);
            List<GradingSubmissionEntity> toFinalize = submissions.stream()
                    .filter(s -> s.getStatus() == SubmissionStatus.SCORED
                            || s.getStatus() == SubmissionStatus.NEED_MORE_EVIDENCE)
                    .toList();

            if (!toFinalize.isEmpty()) {
                List<CompletableFuture<Void>> futures = toFinalize.stream()
                        .map(s -> CompletableFuture.runAsync(() -> {
                            try {
                                submissionService.ensureReviewAndAnnotatedReport(s.getId(), teacherId);
                            } catch (Exception e) {
                                log.error("Failed to finalize resources for submission {} in task {}",
                                        s.getId(), taskId, e);
                            }
                        }, aiExecutor))
                        .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            try {
                batchReviewService.internalGenerate(taskId);
            } catch (Exception e) {
                log.error("Batch review generation failed for task {} during finalize", taskId, e);
                markBatchReviewFailed(taskId);
            }

            markTaskCompleted(taskId);
            log.info("Async finalize completed for task {}", taskId);
        } catch (Exception e) {
            log.error("Async finalize failed for task {}", taskId, e);
            markTaskFailed(taskId);
        }
    }

    @Transactional
    protected void markTaskCompleted(Long taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == GradingTaskStatus.FINALIZING) {
                task.setStatus(GradingTaskStatus.COMPLETED);
                taskRepo.save(task);
            }
            gradingProgressService.broadcastTaskSnapshot(task.getId(), task.getStatus().name(),
                    task.getTotalCount(), task.getCompletedCount(), task.getFailedCount());
        });
    }

    @Transactional
    protected void markTaskFailed(Long taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == GradingTaskStatus.FINALIZING) {
                task.setStatus(GradingTaskStatus.FAILED);
                taskRepo.save(task);
            }
            gradingProgressService.broadcastTaskSnapshot(task.getId(), task.getStatus().name(),
                    task.getTotalCount(), task.getCompletedCount(), task.getFailedCount());
        });
    }

    @Transactional
    protected void markBatchReviewFailed(Long taskId) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.setBatchReviewStatus(GradingTaskEntity.BatchReviewStatus.FAILED);
            taskRepo.save(task);
        });
    }
}
