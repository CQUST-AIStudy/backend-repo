package com.tap.backend.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Auto-starts the PTA spider process on application startup.
 * Only active when the "dev" profile is enabled to prevent side effects
 * during tests and production deployments.
 */
@Component
@Profile("dev")
public class PtaSpiderAutoStarter {

    private static final Logger log = LoggerFactory.getLogger(PtaSpiderAutoStarter.class);
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${pta.auto-start:false}")
    private boolean autoStart;

    @Value("${pta.spider-url:http://127.0.0.1:8100}")
    private String spiderUrl;

    @Value("${pta.start-script:../services/pta_spider/start_spider_api.ps1}")
    private String startScript;

    @Value("${pta.start-timeout-seconds:25}")
    private int startTimeoutSeconds;

    @Value("${pta.auto-stop:true}")
    private boolean autoStop;

    @Value("${pta.stop-script:../services/pta_spider/stop_spider_api.ps1}")
    private String stopScript;

    @Value("${pta.stop-timeout-seconds:12}")
    private int stopTimeoutSeconds;

    private volatile boolean startedByThisApp = false;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!autoStart) {
            log.info("PTA spider auto-start disabled (pta.auto-start=false)");
            return;
        }

        if (isSpiderHealthy()) {
            log.info("PTA spider already running: {}", spiderUrl);
            return;
        }

        Path scriptPath = Paths.get(startScript).toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            log.warn("PTA spider start script not found: {}", scriptPath);
            return;
        }

        List<String> cmd = buildStartCommand(scriptPath.toString());
        log.info("PTA spider not healthy, trying auto-start with command: {}", String.join(" ", cmd));
        try {
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            consumeOutputAsync(process);
        } catch (Exception e) {
            log.warn("Failed to execute PTA spider start script: {}", e.getMessage());
            return;
        }

        long deadline = System.currentTimeMillis() + Math.max(5, startTimeoutSeconds) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isSpiderHealthy()) {
                log.info("PTA spider auto-start succeeded: {}", spiderUrl);
                startedByThisApp = true;
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("PTA spider auto-start timed out ({}s). Please check {}", startTimeoutSeconds, scriptPath);
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        if (!autoStop) {
            log.info("PTA spider auto-stop disabled (pta.auto-stop=false)");
            return;
        }
        if (!startedByThisApp) {
            log.info("Skip PTA spider auto-stop: spider was not started by this app instance");
            return;
        }
        Path scriptPath = Paths.get(stopScript).toAbsolutePath().normalize();
        if (!Files.exists(scriptPath)) {
            log.warn("PTA spider stop script not found: {}", scriptPath);
            return;
        }
        List<String> cmd = buildStartCommand(scriptPath.toString());
        log.info("Stopping PTA spider with command: {}", String.join(" ", cmd));
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            consumeOutputAsync(p);
            p.waitFor(Math.max(3, stopTimeoutSeconds), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to execute PTA spider stop script: {}", e.getMessage());
        }
    }

    private List<String> buildStartCommand(String scriptPath) {
        List<String> cmd = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            cmd.add("powershell.exe");
            cmd.add("-NoProfile");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-File");
            cmd.add(scriptPath);
            return cmd;
        }
        cmd.add("bash");
        cmd.add(scriptPath);
        return cmd;
    }

    private boolean isSpiderHealthy() {
        try {
            ResponseEntity<Map> resp = restTemplate.getForEntity(spiderUrl + "/health", Map.class);
            if (!resp.getStatusCode().is2xxSuccessful()) return false;
            Map body = resp.getBody();
            if (body == null) return true;
            Object status = body.get("status");
            return status == null || "ok".equalsIgnoreCase(String.valueOf(status));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void consumeOutputAsync(Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[PTA Spider Start] {}", line);
                }
            } catch (Exception ignored) {
                // ignore log stream failures
            }
        }, "pta-spider-start-log");
        t.setDaemon(true);
        t.start();
    }
}
