package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StudentSessionResolver {
    private final LegacySessionAccessResolver legacySessionAccessResolver;

    @PersistenceContext
    private EntityManager em;

    public StudentSessionResolver(LegacySessionAccessResolver legacySessionAccessResolver) {
        this.legacySessionAccessResolver = legacySessionAccessResolver;
    }

    public UserEntity requireStudent(HttpServletRequest request) {
        UserEntity user = legacySessionAccessResolver.requireAuthenticated(request);
        if (!"student".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student role required");
        }

        String studentId = resolveStudentNoForUser(user);
        if (studentId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student id missing");
        }
        user.setUsernum(studentId);
        return user;
    }

    public String requireStudentId(HttpServletRequest request) {
        return resolveCanonicalStudentNo(resolveStudentNoForUser(requireStudent(request)));
    }

    public String requireAuthorizedStudentId(String requestedStudentId, HttpServletRequest request) {
        String sessionValue = resolveStudentNoForUser(requireStudent(request));
        String sessionStudentId = resolveCanonicalStudentNo(sessionValue);
        String requestedValue = normalizeStudentId(requestedStudentId);
        if (!sessionStudentId.equals(requestedValue) && !sessionValue.equals(requestedValue)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return sessionStudentId;
    }

    private String resolveCanonicalStudentNo(String studentId) {
        @SuppressWarnings("unchecked")
        List<String> matchedStudentNumbers = em.createNativeQuery(
                "SELECT student_no FROM student_profile WHERE student_no = ?1 OR CAST(id AS CHAR) = ?1 " +
                        "ORDER BY CASE WHEN student_no = ?1 THEN 0 ELSE 1 END LIMIT 1",
                String.class
        ).setParameter(1, studentId).getResultList();
        return matchedStudentNumbers.isEmpty() ? studentId : matchedStudentNumbers.get(0);
    }

    private String resolveStudentNoForUser(UserEntity user) {
        String usernum = normalizeStudentId(user.getUsernum());
        if (usernum != null) {
            return usernum;
        }
        String byProfileUserId = findStudentProfileNoByUserId(user.getId());
        if (byProfileUserId != null) {
            return byProfileUserId;
        }
        return normalizeStudentId(user.getUsername());
    }

    private String findStudentProfileNoByUserId(Integer userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> studentNumbers = em.createNativeQuery(
                    "SELECT student_no FROM student_profile WHERE user_id = ?1 " +
                            "AND student_no IS NOT NULL AND TRIM(student_no) <> '' ORDER BY id ASC LIMIT 1",
                    String.class
            ).setParameter(1, userId).getResultList();
            return studentNumbers.stream()
                    .map(this::normalizeStudentId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeStudentId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
