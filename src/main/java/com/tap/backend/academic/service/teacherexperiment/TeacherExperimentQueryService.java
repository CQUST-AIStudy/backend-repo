package com.tap.backend.academic.service.teacherexperiment;

import com.tap.backend.academic.teacherexperiment.TeacherExperimentListResult;
import com.tap.backend.academic.teacherexperiment.TeacherStudentExperimentResult;

public interface TeacherExperimentQueryService {

    TeacherExperimentListResult getTeacherExperimentList(Integer teacherId, Long classId, String classKeyword);

    TeacherStudentExperimentResult getAllStudentExperiments(Integer teacherId, Long classId, String classKeyword);
}
