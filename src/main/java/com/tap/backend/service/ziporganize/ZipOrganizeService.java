package com.tap.backend.service.ziporganize;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobEntity;
import com.tap.backend.domain.ziporganize.ZipOrganizeJobStatus;
import com.tap.backend.infra.storage.ObjectStorageService;
import com.tap.backend.repo.ZipOrganizeItemRepository;
import com.tap.backend.repo.ZipOrganizeJobRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZipOrganizeService {
  private final ZipOrganizeJobRepository jobRepo;
  private final ZipOrganizeItemRepository itemRepo;
  private final ObjectStorageService storage;
  private final ZipOrganizeProperties props;

  public ZipOrganizeService(ZipOrganizeJobRepository jobRepo,
      ZipOrganizeItemRepository itemRepo,
      ObjectStorageService storage,
      ZipOrganizeProperties props) {
    this.jobRepo = jobRepo;
    this.itemRepo = itemRepo;
    this.storage = storage;
    this.props = props;
  }

  @Transactional
  public ZipOrganizeJobEntity createJob(UserEntity user, String originalFilename, byte[] zipBytes) {
    if (zipBytes == null || zipBytes.length == 0) throw new IllegalArgumentException("zip file is empty");
    long maxZipBytes = props.maxZipBytes() <= 0 ? 50L * 1024 * 1024 : props.maxZipBytes();
    if (zipBytes.length > maxZipBytes) throw new IllegalArgumentException("zip file is too large");

    ZipOrganizeJobEntity job = new ZipOrganizeJobEntity();
    job.setUser(user);
    job.setStatus(ZipOrganizeJobStatus.PENDING);
    job.setOriginalFilename(ZipOrganizeNaming.sanitizeFilename(originalFilename, "zip"));
    job.setInputObjectKey("pending");
    job.setProgress(0);
    job = jobRepo.save(job);

    String inputKey = "zip-organize/%d/input/%s".formatted(job.getId(), job.getOriginalFilename());
    storage.putBytes(inputKey, zipBytes, "application/zip");
    job.setInputObjectKey(inputKey);
    return jobRepo.save(job);
  }

  @Transactional
  public ZipOrganizeJobEntity retry(ZipOrganizeJobEntity job) {
    if (job.getStatus() == ZipOrganizeJobStatus.RUNNING) throw new IllegalArgumentException("job is running");
    int retryLimit = props.retryLimit() <= 0 ? 2 : props.retryLimit();
    if (job.getRetryCount() >= retryLimit) throw new IllegalArgumentException("job retry limit reached");
    itemRepo.deleteAllByJob_Id(job.getId());
    job.setRetryCount(job.getRetryCount() + 1);
    job.setStatus(ZipOrganizeJobStatus.PENDING);
    job.setProgress(0);
    job.setErrorMessage(null);
    job.setStartedAt(null);
    job.setFinishedAt(null);
    job.setCurrentStep(null);
    job.setStepDetail(null);
    job.setZipObjectKey(null);
    job.setReportObjectKey(null);
    job.setResultJson(null);
    return jobRepo.save(job);
  }

  @Transactional
  public void cancel(ZipOrganizeJobEntity job) {
    if (job.getStatus() == ZipOrganizeJobStatus.SUCCEEDED || job.getStatus() == ZipOrganizeJobStatus.FAILED) {
      throw new IllegalArgumentException("job already finished");
    }
    job.setStatus(ZipOrganizeJobStatus.CANCELLED);
    job.setFinishedAt(Instant.now());
    jobRepo.save(job);
  }
}
