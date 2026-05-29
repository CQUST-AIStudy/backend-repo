package com.tap.backend.rag;

import org.springframework.stereotype.Component;

@Component
public class CoverageCalculator {

    private static final double W1 = 0.30; // top1Score weight
    private static final double W2 = 0.20; // evidence count weight
    private static final double W3 = 0.10; // FAQ hit weight
    private static final double W4 = 0.10; // annotation hit weight
    private static final double W5 = 0.30; // query support weight

    public double calculate(double top1Score, int evidenceCount,
                            boolean hitFaq, boolean hitTeacherAnnotation,
                            double querySupportRatio) {
        double score = W1 * Math.min(top1Score, 1.0)
                     + W2 * Math.min(evidenceCount / 5.0, 1.0)
                     + W3 * (hitFaq ? 1.0 : 0.0)
                     + W4 * (hitTeacherAnnotation ? 1.0 : 0.0)
                     + W5 * Math.min(Math.max(querySupportRatio, 0.0), 1.0);
        return Math.max(0.0, Math.min(1.0, score));
    }
}
