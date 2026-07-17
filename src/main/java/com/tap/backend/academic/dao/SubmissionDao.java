package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.Submission;
import com.tap.backend.academic.entity.SubmissionDetailEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SubmissionDao {

    /**
     * 保存提交记录
     * @param submission 提交对象
     * @return 影响的行数
     */
    int saveSubmission(Submission submission);

    int updateReport(@Param("submissionId") int submissionId, @Param("report") String report);

    /**
     * 根据ID查找提交记录
     * @param id 提交ID
     * @return 提交对象
     */
    Submission findSubmissionById(int id);

    /**
     * 根据用户名和实验ID查找提交记录
     * @param username 用户名
     * @param experimentId 实验ID
     * @return 提交对象
     */
    Submission findByUsernameAndExperimentId(String username, int experimentId);

    /**
     * 根据用户名查找所有提交记录
     * @param username 用户名
     * @return 提交记录列表
     */
    List<Submission> findSubmissionsByUsername(String username);

    SubmissionDetailEntity findDetailByUsernameAndExperimentId(String username, int experimentId);
}
