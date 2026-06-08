package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.StudentDao;
import com.tap.backend.academic.dao.UserDao;
import com.tap.backend.academic.entity.Student;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.service.UserService;
import com.tap.backend.domain.classroom.ClassStudentEntity;
import com.tap.backend.domain.classroom.TeachingClassEntity;
import com.tap.backend.repo.ClassStudentRepository;
import com.tap.backend.repo.TeachingClassRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserDao userDao;

    @Autowired
    private StudentDao studentDao;

    @Autowired(required = false)
    private TeachingClassRepository teachingClassRepo;

    @Autowired(required = false)
    private ClassStudentRepository classStudentRepo;

    @PersistenceContext
    private EntityManager em;

    @Override
    public UserEntity findByUsername(String username) {
        // 1. 先查 tap_user 表（教师/管理员优先）
        UserEntity tapUser = userDao.findByUsername(username);
        if (tapUser != null) {
            return tapUser; // 教师/管理员从 tap_user 登录
        }
        
        // 2. 查 user 表（仅学生）
        UserEntity legacyUser = userDao.findByUsernameFromLegacyUser(username);
        if (legacyUser != null) {
            // 学生认证通过，需要用 usernum 找到对应的 tap_user 记录
            UserEntity tapUserByUsernum = userDao.findTapUserByUsernum(legacyUser.getUsernum());
            if (tapUserByUsernum != null) {
                // 用 tap_user 的信息建立会话，但保留 user 表的正确密码
                tapUserByUsernum.setPassword(legacyUser.getPassword());
                return tapUserByUsernum;
            }
            // 如果 tap_user 中没有对应记录，返回 legacyUser（降级处理）
            return legacyUser;
        }
        
        return null; // 用户不存在
    }

    @Override
    public boolean saveUser(UserEntity user) {
        return userDao.saveUser(user) > 0;
    }

    @Override
    public UserEntity findById(int id) {
        return userDao.findById(id);
    }

    @Override
    public boolean updateUser(UserEntity user) {
        return userDao.updateUser(user) > 0;
    }

    @Override
    public boolean deleteUser(int id) {
        return userDao.deleteUser(id) > 0;
    }

    @Override
    public void bindStudentByUsernum(String username, String usernum, String classname) {
        // 尝试通过学号查找已有学生
        Student existing = studentDao.findByStudentId(Integer.parseInt(usernum));
        if (existing != null) {
            // 学生已存在，更新 username 关联
            studentDao.bindUsernameByStudentId(usernum, username);
        } else {
            // 学生不存在，创建新记录
            studentDao.insertStudent(usernum, username, username, classname);
        }

        // 新增：同时往 class_student 表里写入记录
        if (teachingClassRepo != null && classStudentRepo != null
                && classname != null && !classname.isBlank()) {
            try {
                // 根据 classname 查找 teaching_class（按 name/class_code/course_name 模糊匹配）
                List<TeachingClassEntity> matchedClasses = teachingClassRepo.findByNameContainingOrderByIdAsc(classname);
                if (matchedClasses == null || matchedClasses.isEmpty()) {
                    // 尝试按 class_code 精确匹配
                    var byCode = teachingClassRepo.findByClassCode(classname);
                    if (byCode.isPresent()) {
                        matchedClasses = List.of(byCode.get());
                    }
                }
                if (matchedClasses != null && !matchedClasses.isEmpty()) {
                    TeachingClassEntity teachingClass = matchedClasses.get(0);

                    // 检查 class_student 表里是否已有记录（避免重复插入）
                    boolean exists = classStudentRepo.existsByClassIdAndStudentNum(teachingClass.getId(), usernum);
                    if (!exists) {
                        // 往 class_student 表里插入记录
                        ClassStudentEntity classStudent = new ClassStudentEntity();
                        classStudent.setTeachingClass(teachingClass);
                        classStudent.setStudentName(username);
                        classStudent.setStudentNum(usernum);

                        // 获取当前用户 ID
                        UserEntity user = userDao.findByUsername(username);
                        if (user != null) {
                            classStudent.setUserId((long) user.getId());
                        }

                        classStudentRepo.save(classStudent);
                    }
                }
            } catch (Exception e) {
                // 记录日志但不中断注册流程
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("bindStudentByUsernum: failed to sync to class_student: {}", e.getMessage());
            }
        }

        // 新增：同时往 student_profile 表里写入/更新记录（学号为唯一键）
        try {
            @SuppressWarnings("unchecked")
            List<Number> tapUserIds = em.createNativeQuery(
                    "SELECT id FROM tap_user WHERE username = ?1", Number.class)
                    .setParameter(1, username)
                    .getResultList();
            if (!tapUserIds.isEmpty()) {
                long tapUserId = tapUserIds.get(0).longValue();
                em.createNativeQuery(
                        "INSERT INTO student_profile (student_no, real_name, user_id, status) " +
                                "VALUES (?1, ?2, ?3, 'ACTIVE') " +
                                "ON DUPLICATE KEY UPDATE user_id = COALESCE(student_profile.user_id, VALUES(user_id))")
                        .setParameter(1, usernum)
                        .setParameter(2, username)
                        .setParameter(3, tapUserId)
                        .executeUpdate();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "bindStudentByUsernum: failed to sync to student_profile: {}", e.getMessage());
        }
    }
}
