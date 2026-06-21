package com.tap.backend.domain.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GradingSubmissionPublishStateTest {

    @Test
    void newSubmissionStartsUnmatchedAndUnpublished() {
        GradingSubmissionEntity submission = new GradingSubmissionEntity();

        assertEquals(SubmissionMatchStatus.UNMATCHED, submission.getMatchStatus());
        assertNull(submission.getPublishedAt());
        assertNull(submission.getPublishedBy());
    }

    @Test
    void publicationFieldsCanBePersistedOnEntity() {
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        Instant publishedAt = Instant.parse("2026-06-20T08:00:00Z");

        submission.setMatchStatus(SubmissionMatchStatus.MANUAL_CONFIRMED);
        submission.setPublishedAt(publishedAt);
        submission.setPublishedBy(7L);

        assertEquals(SubmissionMatchStatus.MANUAL_CONFIRMED, submission.getMatchStatus());
        assertEquals(publishedAt, submission.getPublishedAt());
        assertEquals(7L, submission.getPublishedBy());
    }
}
