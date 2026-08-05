package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

        String studentId = firstNonBlank(user.getUsernum(), user.getUsername());
        if (studentId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student id missing");
        }
        return user;
    }

    public String requireStudentId(HttpServletRequest request) {
        return resolveStudentNo(requireStudent(request));
    }

    public Integer requireStudentProfileId(HttpServletRequest request) {
        UserEntity user = requireStudent(request);
        String studentId = firstNonBlank(user.getUsernum(), user.getUsername());
        @SuppressWarnings("unchecked")
        List<Object> matchedProfileIds = em.createNativeQuery(
                "SELECT id FROM student_profile " +
                        "WHERE user_id = ?1 OR student_no = ?2 OR CAST(id AS CHAR) = ?2 " +
                        "ORDER BY CASE WHEN user_id = ?1 THEN 0 WHEN student_no = ?2 THEN 1 ELSE 2 END LIMIT 1"
        ).setParameter(1, user.getId() > 0 ? user.getId() : null)
                .setParameter(2, studentId)
                .getResultList();
        if (matchedProfileIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student profile missing");
        }

        Object profileId = matchedProfileIds.get(0);
        if (profileId instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(profileId));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid student profile id");
        }
    }

    public void requireActiveClassMembership(Integer studentProfileId, Long classId) {
        if (studentProfileId == null || studentProfileId <= 0 || classId == null || classId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "class id is invalid");
        }
        Number membershipCount = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM class_member cm " +
                        "JOIN teaching_class tc ON tc.id = cm.class_id " +
                        "WHERE cm.student_id = ?1 AND cm.class_id = ?2 " +
                        "AND cm.member_status = 'ACTIVE' AND (tc.status IS NULL OR tc.status = 'ACTIVE')"
        ).setParameter(1, studentProfileId)
                .setParameter(2, classId)
                .getSingleResult();
        if (membershipCount == null || membershipCount.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student is not in this class");
        }
    }

    public String requireAuthorizedStudentId(String requestedStudentId, HttpServletRequest request) {
        UserEntity user = requireStudent(request);
        String sessionValue = firstNonBlank(user.getUsernum(), user.getUsername());
        String sessionStudentId = resolveStudentNo(user);
        String requestedValue = normalizeStudentId(requestedStudentId);
        if (!sessionStudentId.equals(requestedValue) && !sessionValue.equals(requestedValue)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return sessionStudentId;
    }

    private String resolveStudentNo(UserEntity user) {
        String studentId = firstNonBlank(user.getUsernum(), user.getUsername());
        if (studentId == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> matchedStudentNumbers = em.createNativeQuery(
                "SELECT student_no FROM student_profile " +
                        "WHERE user_id = ?1 OR student_no = ?2 OR CAST(id AS CHAR) = ?2 " +
                        "ORDER BY CASE WHEN user_id = ?1 THEN 0 WHEN student_no = ?2 THEN 1 ELSE 2 END LIMIT 1",
                String.class
        ).setParameter(1, user.getId() > 0 ? user.getId() : null)
                .setParameter(2, studentId)
                .getResultList();
        return matchedStudentNumbers.isEmpty() ? studentId : matchedStudentNumbers.get(0);
    }

    private String firstNonBlank(String primary, String fallback) {
        String normalized = normalizeStudentId(primary);
        return normalized != null ? normalized : normalizeStudentId(fallback);
    }

    private String normalizeStudentId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
