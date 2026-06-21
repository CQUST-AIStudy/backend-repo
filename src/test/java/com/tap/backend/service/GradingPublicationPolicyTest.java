package com.tap.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.GradingTaskEntity;
import com.tap.backend.domain.grading.SubmissionMatchStatus;
import com.tap.backend.domain.grading.SubmissionStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GradingPublicationPolicyTest {

    private final GradingPublicationPolicy policy = new GradingPublicationPolicy();

    @Test
    void rejectsUnconfirmedStudentMatch() {
        GradingSubmissionEntity submission = publishableSubmission();
        submission.setMatchStatus(SubmissionMatchStatus.AMBIGUOUS);

        assertThrows(IllegalStateException.class, () -> policy.validatePublish(submission, 7L));
    }

    @Test
    void rejectsTeacherWhoDoesNotOwnTask() {
        GradingSubmissionEntity submission = publishableSubmission();

        assertThrows(IllegalArgumentException.class, () -> policy.validatePublish(submission, 8L));
    }

    @Test
    void acceptsOwnedScoredConfirmedSubmission() {
        assertDoesNotThrow(() -> policy.validatePublish(publishableSubmission(), 7L));
    }

    private GradingSubmissionEntity publishableSubmission() {
        GradingTaskEntity task = new GradingTaskEntity();
        ReflectionTestUtils.setField(task, "teacherId", 7L);
        GradingSubmissionEntity submission = new GradingSubmissionEntity();
        submission.setTask(task);
        submission.setStudentId(11L);
        submission.setMatchStatus(SubmissionMatchStatus.AUTO_CONFIRMED);
        submission.setStatus(SubmissionStatus.SCORED);
        submission.setTotalScore(new BigDecimal("86"));
        return submission;
    }
}
