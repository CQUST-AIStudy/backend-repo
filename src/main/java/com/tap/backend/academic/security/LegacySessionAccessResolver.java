package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class LegacySessionAccessResolver {
    private final UserRepository userRepository;
    private final LegacyAuthTokenService legacyAuthTokenService;

    @PersistenceContext
    private EntityManager em;

    public LegacySessionAccessResolver(UserRepository userRepository, LegacyAuthTokenService legacyAuthTokenService) {
        this.userRepository = userRepository;
        this.legacyAuthTokenService = legacyAuthTokenService;
    }

    public UserEntity requireAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object currentUser = session.getAttribute("currentUser");
            if (currentUser instanceof UserEntity user) {
                return user;
            }
        }

        var cookieUser = legacyAuthTokenService.resolve(request);
        if (cookieUser.isPresent()) {
            return cookieUser.get();
        }

        UserPrincipal principal = currentJwtPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }

        com.tap.backend.domain.user.UserEntity tapUser = userRepository.findById(principal.userId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (!Boolean.TRUE.equals(tapUser.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user account is disabled");
        }
        return toLegacyUser(tapUser);
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

    private UserPrincipal currentJwtPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private UserEntity toLegacyUser(com.tap.backend.domain.user.UserEntity tapUser) {
        UserEntity legacyUser = findLegacyUser(tapUser.getUsername());
        if (legacyUser != null) {
            return legacyUser;
        }

        UserEntity user = new UserEntity();
        user.setId(tapUser.getId() == null ? 0 : tapUser.getId().intValue());
        user.setUsername(tapUser.getUsername());
        user.setRole(tapUser.getRole() == null ? null : tapUser.getRole().name().toLowerCase());
        user.setUsernum(tapUser.getUsername());
        user.setClassname(null);
        return user;
    }

    private UserEntity findLegacyUser(String username) {
        String normalizedUsername = normalizeKeepCase(username);
        if (normalizedUsername == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT id, username, password, email, role, usernum, classname FROM `user` WHERE username = ?1 LIMIT 1"
                )
                .setParameter(1, normalizedUsername)
                .getResultList();
            if (rows.isEmpty()) {
                return null;
            }
            Object[] row = rows.get(0);
            UserEntity user = new UserEntity();
            user.setId(toInt(row[0]));
            user.setUsername(toString(row[1]));
            user.setPassword(toString(row[2]));
            user.setEmail(toString(row[3]));
            user.setRole(toString(row[4]));
            user.setUsernum(toString(row[5]));
            user.setClassname(toString(row[6]));
            return user;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String normalizeKeepCase(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String toString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
