package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StudentSessionResolver {

    @PersistenceContext
    private EntityManager em;

    public UserEntity requireStudent(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }

        Object currentUser = session.getAttribute("currentUser");
        if (!(currentUser instanceof UserEntity user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        if (!"student".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student role required");
        }

        String studentId = normalizeStudentId(user.getUsernum());
        if (studentId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student id missing");
        }
        return user;
    }

    public String requireStudentId(HttpServletRequest request) {
        return resolveCanonicalStudentNo(normalizeStudentId(requireStudent(request).getUsernum()));
    }

    public String requireAuthorizedStudentId(String requestedStudentId, HttpServletRequest request) {
        String sessionValue = normalizeStudentId(requireStudent(request).getUsernum());
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

    private String normalizeStudentId(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
