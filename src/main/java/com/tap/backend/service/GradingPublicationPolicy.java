package com.tap.backend.service;

import com.tap.backend.domain.grading.GradingSubmissionEntity;
import com.tap.backend.domain.grading.SubmissionStatus;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class GradingPublicationPolicy {

    public void validatePublish(GradingSubmissionEntity submission, Long teacherId) {
        if (submission == null || submission.getTask() == null
                || !Objects.equals(submission.getTask().getTeacherId(), teacherId)) {
            throw new IllegalArgumentException("提交不存在");
        }
        if (submission.getMatchStatus() == null || !submission.getMatchStatus().isConfirmed()
                || submission.getStudentId() == null) {
            throw new IllegalStateException("请先确认学生匹配");
        }
        if (submission.getStatus() != SubmissionStatus.SCORED || submission.getTotalScore() == null) {
            throw new IllegalStateException("该提交尚未完成评分");
        }
    }
}
