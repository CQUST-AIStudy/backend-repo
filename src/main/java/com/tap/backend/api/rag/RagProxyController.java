package com.tap.backend.api.rag;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.JwtService;
import com.tap.backend.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/rag")
public class RagProxyController {

    private final RagProxyService ragProxyService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public RagProxyController(
            RagProxyService ragProxyService,
            JwtService jwtService,
            UserRepository userRepository) {
        this.ragProxyService = ragProxyService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @GetMapping({"", "/**"})
    public void proxyGet(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        forward(request, response, resolvePrincipal(authentication));
    }

    @PostMapping({"", "/**"})
    public void proxyPost(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        forward(request, response, resolvePrincipal(authentication));
    }

    @PutMapping({"", "/**"})
    public void proxyPut(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        forward(request, response, resolvePrincipal(authentication));
    }

    @DeleteMapping({"", "/**"})
    public void proxyDelete(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        forward(request, response, resolvePrincipal(authentication));
    }

    void forward(HttpServletRequest request, HttpServletResponse response, UserPrincipal principal) {
        requireAuthenticated(principal);
        enforceProxyAccess(request, principal);
        ragProxyService.forward(request, response, issueProxyToken(principal));
    }

    private UserPrincipal resolvePrincipal(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw unauthorized("RAG proxy authentication required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        if (principal instanceof com.tap.backend.academic.entity.UserEntity legacyUser) {
            return resolveLegacyPrincipal(legacyUser);
        }
        throw unauthorized("RAG proxy authentication required");
    }

    private UserPrincipal resolveLegacyPrincipal(com.tap.backend.academic.entity.UserEntity legacyUser) {
        if (legacyUser == null || legacyUser.getUsername() == null || legacyUser.getUsername().isBlank()) {
            throw unauthorized("RAG proxy authentication required");
        }
        String username = legacyUser.getUsername().trim();
        UserRole role = mapRole(legacyUser.getRole());
        UserEntity user = userRepository.findByUsername(username)
                .map(existing -> {
                    if (!Boolean.TRUE.equals(existing.getEnabled())) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "current login user is disabled");
                    }
                    return existing;
                })
                .orElseGet(() -> createProxyUser(username, role));
        return new UserPrincipal(user.getId(), user.getUsername(), user.getRole());
    }

    private UserEntity createProxyUser(String username, UserRole role) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private String issueProxyToken(UserPrincipal principal) {
        UserEntity user = new UserEntity();
        user.setUsername(principal.username());
        user.setRole(principal.role());
        user.setEnabled(true);
        setUserId(user, principal.userId());
        return jwtService.issue(user);
    }

    private void requireAuthenticated(UserPrincipal principal) {
        if (principal == null) {
            throw unauthorized("RAG proxy authentication required");
        }
    }

    private void enforceProxyAccess(HttpServletRequest request, UserPrincipal principal) {
        if (!requiresTeacherOrAdmin(request)) {
            return;
        }
        if (principal == null) {
            throw unauthorized("RAG proxy authentication required");
        }
        if (principal.role() != UserRole.ADMIN && principal.role() != UserRole.TEACHER) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "teacher or admin role required for this RAG operation");
        }
    }

    private boolean requiresTeacherOrAdmin(HttpServletRequest request) {
        String requestUri = request == null ? "" : request.getRequestURI();
        String method = request == null ? "" : request.getMethod();

        if ("POST".equalsIgnoreCase(method)) {
            return requestUri.endsWith("/knowledge-base")
                    || requestUri.endsWith("/document/upload")
                    || requestUri.contains("/documents/reprocess")
                    || requestUri.endsWith("/annotations")
                    || requestUri.endsWith("/rebuild-bm25");
        }
        if ("PUT".equalsIgnoreCase(method)) {
            return requestUri.contains("/knowledge-base/");
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            return requestUri.contains("/knowledge-base/")
                    || requestUri.contains("/document/")
                    || requestUri.contains("/annotations/");
        }
        return false;
    }

    private UserRole mapRole(String role) {
        if (role == null || role.isBlank()) {
            throw unauthorized("unsupported legacy role for RAG proxy");
        }
        return switch (role.trim().toUpperCase()) {
            case "ADMIN" -> UserRole.ADMIN;
            case "TEACHER" -> UserRole.TEACHER;
            case "STUDENT" -> UserRole.STUDENT;
            default -> throw unauthorized("unsupported legacy role for RAG proxy");
        };
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    private void setUserId(UserEntity user, Long userId) {
        try {
            var field = UserEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, userId);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to prepare proxy user identity", ex);
        }
    }
}
