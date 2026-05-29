package com.tap.backend.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {
  @Bean(destroyMethod = "shutdown")
  public ExecutorService agentDocExecutor(AgentProperties props) {
    int n = props.docMaxConcurrency() <= 0 ? 4 : props.docMaxConcurrency();
    return Executors.newFixedThreadPool(n, r -> {
      Thread t = new Thread(r);
      t.setName("tap-agent-doc-" + t.getId());
      t.setDaemon(true);
      return t;
    });
  }
}
