package com.tap.backend.academic.service;

import com.tap.backend.academic.entity.Experiment;
import java.util.List;
import java.util.Map;

public interface ExperimentService {
    
    /**
     * 查找所有实验
     * @return 实验列表
     */
    List<Experiment> findAllExperiments();
    
    /**
     * 根据ID查找实验
     * @param id 实验ID
     * @return 实验对象
     */
    Experiment findExperimentById(int id);
    
    /**
     * 根据实验编号查找实验
     * @param num 实验编号
     * @return 实验对象
     */
    Experiment findExperimentByNum(int num);
    
    /**
     * 保存实验信息
     * @param experiment 实验对象
     * @return 是否保存成功
     */
    boolean saveExperiment(Experiment experiment);
    
    /**
     * 更新实验信息
     * @param experiment 实验对象
     * @return 是否更新成功
     */
    boolean updateExperiment(Experiment experiment);
    
    /**
     * 删除实验
     * @param id 实验ID
     * @return 是否删除成功
     */
    boolean deleteExperiment(int id);
    
    /**
     * 提交实验
     * @param id 实验ID
     * @param username 用户名
     * @param code 代码
     * @param report 报告
     * @return 是否提交成功
     */
    boolean submitExperiment(int id, String username, String code, String report);
    
    /**
     * 获取指定用户的所有实验
     * @param username 用户名
     * @return 实验信息列表
     */
    List<Map<String, Object>> findExperimentsByUsername(String username);
    
    /**
     * 根据老师ID获取所有实验列表
     * @param teacherId 老师ID
     * @return 实验列表
     */
    List<Experiment> findExperimentsByTeacherId(String teacherId);
}