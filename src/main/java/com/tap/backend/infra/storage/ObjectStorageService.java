package com.tap.backend.infra.storage;

import io.minio.*;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class ObjectStorageService {
  private final MinioClient minioClient;
  private final S3Properties props;

  public ObjectStorageService(MinioClient minioClient, S3Properties props) {
    this.minioClient = minioClient;
    this.props = props;
  }

  public void putBytes(String objectKey, byte[] bytes, String contentType) {
    try {
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(props.bucket())
              .object(objectKey)
              .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
              .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
              .build()
      );
    } catch (Exception e) {
      throw new IllegalStateException("minio putObject failed: " + objectKey, e);
    }
  }

  public byte[] getBytes(String objectKey) {
    try (var stream = minioClient.getObject(
        GetObjectArgs.builder().bucket(props.bucket()).object(objectKey).build()
    )) {
      return stream.readAllBytes();
    } catch (Exception e) {
      throw new IllegalStateException("minio getObject failed: " + objectKey, e);
    }
  }

  /** Copy an object within the same bucket */
  public void copyObject(String sourceKey, String targetKey) {
    try {
      minioClient.copyObject(
          CopyObjectArgs.builder()
              .bucket(props.bucket())
              .object(targetKey)
              .source(CopySource.builder().bucket(props.bucket()).object(sourceKey).build())
              .build()
      );
    } catch (Exception e) {
      throw new IllegalStateException("minio copyObject failed: " + sourceKey + " -> " + targetKey, e);
    }
  }

  /** Check if an object exists */
  public boolean exists(String objectKey) {
    try {
      minioClient.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Get a presigned download URL valid for the given duration */
  public String getPresignedUrl(String objectKey, int expirySeconds) {
    try {
      return minioClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(props.bucket())
              .object(objectKey)
              .expiry(expirySeconds, TimeUnit.SECONDS)
              .build()
      );
    } catch (Exception e) {
      throw new IllegalStateException("minio presigned URL failed: " + objectKey, e);
    }
  }

  public String getBucket() { return props.bucket(); }
}
