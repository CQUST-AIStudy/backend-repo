package com.tap.backend.service.ziporganize;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ZipOrganizeConfig {
  @Bean
  @Qualifier("zipOrganizeItemExecutor")
  public ExecutorService zipOrganizeItemExecutor(ZipOrganizeProperties props) {
    int n = props.itemMaxConcurrency() <= 0 ? 3 : props.itemMaxConcurrency();
    return Executors.newFixedThreadPool(n, r -> {
      Thread t = new Thread(r);
      t.setName("tap-zip-organize-item-" + t.getId());
      t.setDaemon(true);
      return t;
    });
  }
}
