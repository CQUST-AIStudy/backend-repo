package com.tap.backend.academic.teacherexperiment;

import com.tap.backend.academic.entity.teacher.TeacherExperiment;
import java.util.Collections;
import java.util.List;

public class TeacherExperimentListResult {

    private final List<TeacherExperiment> experiments;
    private final int studentCount;

    public TeacherExperimentListResult(List<TeacherExperiment> experiments, int studentCount) {
        this.experiments = experiments == null ? Collections.emptyList() : experiments;
        this.studentCount = studentCount;
    }

    public List<TeacherExperiment> getExperiments() {
        return experiments;
    }

    public int getStudentCount() {
        return studentCount;
    }
}
