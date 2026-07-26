package com.tap.backend.academic;

import com.tap.backend.academic.entity.LeetCodeProblemTag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LeetCodeProblemTagContractTest {

    @Test
    void entityUsesCanonicalV12Fields() {
        LeetCodeProblemTag tag = new LeetCodeProblemTag(
                7L, "algorithm", "动态规划", new BigDecimal("0.9500"));
        tag.setPrimary(true);

        assertThat(tag.getProblemId()).isEqualTo(7L);
        assertThat(tag.getTagCategory()).isEqualTo("algorithm");
        assertThat(tag.getTagName()).isEqualTo("动态规划");
        assertThat(tag.getRelevanceScore()).isEqualByComparingTo("0.9500");
        assertThat(tag.getPrimary()).isTrue();
    }
}
