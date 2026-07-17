package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.Experiment;
import com.tap.backend.academic.entity.Submission;
import java.util.Map;

public interface AiReportGenerator {
    String generate(Experiment experiment, Submission submission, Map<String, Object> userData) throws Exception;
}
