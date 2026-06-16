package com.tap.backend.academic.service.teacherexperiment;

import com.tap.backend.academic.teacherexperiment.TeacherExperimentListResult;
import com.tap.backend.academic.teacherexperiment.TeacherStudentExperimentResult;

public interface TeacherExperimentQueryService {

    TeacherExperimentListResult getTeacherExperimentList(Integer teacherId, Long classId, String classKeyword);

    TeacherExperimentListResult getTeacherExperimentListForAdmin(Long classId, String classKeyword, String scope);

    TeacherStudentExperimentResult getAllStudentExperiments(Integer teacherId, Long classId, String classKeyword, Integer experimentId);

    /**
     * 管理员视角：全量学生 × 全量实验笛卡尔积，支持 classId / classKeyword / experimentId 过滤。
     * 复用 score + submit_situation 兜底查分逻辑（与教师流一致）。
     * scope="all" 时跳过课程范围过滤，返回所有实验（用于管理员大屏）。
     */
    TeacherStudentExperimentResult getAllStudentExperimentsForAdmin(Long classId, String classKeyword, Integer experimentId, String scope);
}
