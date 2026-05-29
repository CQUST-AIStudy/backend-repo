package com.tap.backend.infra.storage;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {
  @Bean
  public MinioClient minioClient(S3Properties props) {
    return MinioClient.builder()
        .endpoint(props.endpoint())
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }
}
