package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LegacySessionAccessResolver {

    public UserEntity requireAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }

        Object currentUser = session.getAttribute("currentUser");
        if (!(currentUser instanceof UserEntity user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return user;
    }

    public String requireStudentReadAccess(String requestedStudentId, HttpServletRequest request) {
        UserEntity user = requireAuthenticated(request);
        String role = normalize(user.getRole());
        String normalizedStudentId = normalize(requestedStudentId);
        if (normalizedStudentId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "student id required");
        }

        if ("teacher".equals(role) || "admin".equals(role)) {
            return normalizedStudentId;
        }
        if (!"student".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }

        String currentStudentId = normalize(user.getUsernum());
        if (!normalizedStudentId.equals(currentStudentId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return normalizedStudentId;
    }

    public String requireUsernameReadAccess(String requestedUsername, HttpServletRequest request) {
        UserEntity user = requireAuthenticated(request);
        String role = normalize(user.getRole());
        String normalizedUsername = normalize(requestedUsername);
        if (normalizedUsername == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username required");
        }

        if ("teacher".equals(role) || "admin".equals(role)) {
            return normalizedUsername;
        }
        if (!"student".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }

        String currentUsername = normalize(user.getUsername());
        if (!normalizedUsername.equals(currentUsername)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return normalizedUsername;
    }

    public UserEntity requireTeacherOrAdmin(HttpServletRequest request) {
        UserEntity user = requireAuthenticated(request);
        String role = normalize(user.getRole());
        if (!"teacher".equals(role) && !"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teacher role required");
        }
        return user;
    }

    public UserEntity requireAdmin(HttpServletRequest request) {
        UserEntity user = requireAuthenticated(request);
        String role = normalize(user.getRole());
        if (!"admin".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin role required");
        }
        return user;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
