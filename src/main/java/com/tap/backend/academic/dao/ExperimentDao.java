package com.tap.backend.academic.dao;

import com.tap.backend.academic.entity.Experiment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ExperimentDao {
    
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
     * 根据老师ID查找实验列表
     * @param teacherId 老师ID
     * @return 实验列表
     */
    List<Experiment> findExperimentsByTeacherId(String teacherId);

    List<Experiment> findExperimentsByClassKeyword(
            @Param("classKeyword") String classKeyword,
            @Param("teacherId") String teacherId
    );
    
    /**
     * 保存实验信息
     * @param experiment 实验对象
     * @return 影响的行数
     */
    int saveExperiment(Experiment experiment);
    
    /**
     * 更新实验信息
     * @param experiment 实验对象
     * @return 影响的行数
     */
    int updateExperiment(Experiment experiment);
    
    /**
     * 删除实验
     * @param id 实验ID
     * @return 影响的行数
     */
    int deleteExperiment(int id);
}
