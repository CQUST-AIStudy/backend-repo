package com.tap.backend.academic.service;

import com.tap.backend.academic.teacherexperiment.AiReportContext;
import java.util.Map;

public interface AiReportGenerator {
    String generate(AiReportContext context, String code, Map<String, Object> userData) throws Exception;
}
