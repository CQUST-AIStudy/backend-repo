package com.tap.backend.service.ziporganize;

import com.tap.backend.domain.ziporganize.ZipOrganizeJobEntity;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobStatus;
import com.tap.backend.repo.ZipOrganizeJobRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ZipOrganizeScheduler {
  private final ZipOrganizeJobRepository jobRepo;
  private final ZipOrganizeRunner runner;
  private final ExecutorService jobExecutor;
  private final Semaphore slots;

  public ZipOrganizeScheduler(ZipOrganizeJobRepository jobRepo,
      ZipOrganizeRunner runner,
      ZipOrganizeProperties props) {
    this.jobRepo = jobRepo;
    this.runner = runner;
    this.jobExecutor = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r);
      t.setName("tap-zip-organize-job-" + t.getId());
      t.setDaemon(true);
      return t;
    });
    int maxJobs = props.jobMaxConcurrency() <= 0 ? 1 : props.jobMaxConcurrency();
    this.slots = new Semaphore(maxJobs);
  }

  @PreDestroy
  public void shutdown() {
    jobExecutor.shutdown();
  }

  @Scheduled(fixedDelayString = "${tap.zip-organize.poll-interval-ms:2000}")
  public void poll() {
    if (!slots.tryAcquire()) return;
    jobExecutor.execute(() -> {
      try {
        Long claimed = claimNextPendingJobId();
        if (claimed != null) runner.runJob(claimed);
      } finally {
        slots.release();
      }
    });
  }

  @Transactional
  protected Long claimNextPendingJobId() {
    ZipOrganizeJobEntity job;
    try {
      job = jobRepo.findFirstByStatusOrderByCreatedAtAsc(ZipOrganizeJobStatus.PENDING);
    } catch (Exception e) {
      return null;
    }
    if (job == null) return null;
    job.setStatus(ZipOrganizeJobStatus.RUNNING);
    job.setStartedAt(Instant.now());
    job.setFinishedAt(null);
    job.setErrorMessage(null);
    job.setProgress(Math.max(job.getProgress(), 1));
    jobRepo.save(job);
    return job.getId();
  }
}
