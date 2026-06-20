package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.academic.entity.teacher.Teacher;
import com.tap.backend.academic.service.TeacherService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TeacherSessionResolver {

    private final LegacySessionAccessResolver legacySessionAccessResolver;
    private final TeacherService teacherService;

    public TeacherSessionResolver(
            LegacySessionAccessResolver legacySessionAccessResolver,
            TeacherService teacherService) {
        this.legacySessionAccessResolver = legacySessionAccessResolver;
        this.teacherService = teacherService;
    }

    public Teacher requireCurrentTeacher(HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireTeacherOrAdmin(request);
        String username = normalize(user.getUsername());
        Teacher teacher = username == null ? null : teacherService.findByUsername(username);
        if (teacher == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teacher info not found");
        }
        return teacher;
    }

    /**
     * 尝试获取当前 Teacher：admin 角色但没有 Teacher 记录时返回 null（不抛异常），
     * teacher 角色但没有 Teacher 记录时仍抛 403。
     * 供管理员视角的只读接口使用，例如查看全量学生/全量实验。
     */
    public Teacher requireCurrentTeacherOrAdminNull(HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireTeacherOrAdmin(request);
        String role = normalize(user.getRole());
        String username = normalize(user.getUsername());
        Teacher teacher = username == null ? null : teacherService.findByUsername(username);
        if (teacher == null && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teacher info not found");
        }
        return teacher;
    }

    public boolean isCurrentAdmin(HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireTeacherOrAdmin(request);
        return "admin".equals(normalize(user.getRole()));
    }

    public Teacher requireTeacherAccess(Integer teacherId, HttpServletRequest request) {
        if (teacherId == null || teacherId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "teacher id required");
        }

        UserEntity user = legacySessionAccessResolver.requireTeacherOrAdmin(request);
        String role = normalize(user.getRole());
        if ("admin".equals(role)) {
            Teacher teacher = teacherService.findByTeacherId(teacherId);
            if (teacher == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "teacher info not found");
            }
            return teacher;
        }

        Teacher currentTeacher = requireCurrentTeacher(request);
        if (!teacherId.equals(currentTeacher.getTeacher_id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return currentTeacher;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
