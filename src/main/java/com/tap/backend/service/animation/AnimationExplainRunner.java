package com.tap.backend.service.animation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** 异步执行动画讲解生成流水线（独立 Bean 以生效 @Async）。 */
@Component
public class AnimationExplainRunner {

    private static final Logger log = LoggerFactory.getLogger(AnimationExplainRunner.class);

    private final AnimationExplainService explainService;

    public AnimationExplainRunner(AnimationExplainService explainService) {
        this.explainService = explainService;
    }

    @Async("aiExecutor")
    public void runAsync(Long explainId) {
        try {
            explainService.runPipeline(explainId);
        } catch (Exception e) {
            log.error("Animation explain failed: {}", explainId, e);
            explainService.markFailed(explainId, e.getMessage());
        }
    }
}
