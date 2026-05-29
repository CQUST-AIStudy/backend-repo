package com.tap.backend.academic.teacherexperiment;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TeacherStudentExperimentResult {

    private final boolean hasStudents;
    private final List<Map<String, Object>> data;

    public TeacherStudentExperimentResult(boolean hasStudents, List<Map<String, Object>> data) {
        this.hasStudents = hasStudents;
        this.data = data == null ? Collections.emptyList() : data;
    }

    public boolean hasStudents() {
        return hasStudents;
    }

    public List<Map<String, Object>> getData() {
        return data;
    }
}
