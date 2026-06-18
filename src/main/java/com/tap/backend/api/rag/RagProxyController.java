package com.tap.backend.api.rag;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.http.HttpStatus;
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

    private final LegacySessionAccessResolver legacySessionAccessResolver;
    private final RagProxyService ragProxyService;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;
    private final SecretKey ragJwtKey;

    public RagProxyController(
            LegacySessionAccessResolver legacySessionAccessResolver,
            RagProxyService ragProxyService,
            UserRepository userRepository,
            JwtProperties jwtProperties) {
        this.legacySessionAccessResolver = legacySessionAccessResolver;
        this.ragProxyService = ragProxyService;
        this.userRepository = userRepository;
        this.jwtProperties = jwtProperties;
        this.ragJwtKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping({"", "/**"})
    public void proxyGet(HttpServletRequest request, HttpServletResponse response) {
        forward(request, response);
    }

    @PostMapping({"", "/**"})
    public void proxyPost(HttpServletRequest request, HttpServletResponse response) {
        forward(request, response);
    }

    @PutMapping({"", "/**"})
    public void proxyPut(HttpServletRequest request, HttpServletResponse response) {
        forward(request, response);
    }

    @DeleteMapping({"", "/**"})
    public void proxyDelete(HttpServletRequest request, HttpServletResponse response) {
        forward(request, response);
    }

    private void forward(HttpServletRequest request, HttpServletResponse response) {
        com.tap.backend.academic.entity.UserEntity legacyUser = legacySessionAccessResolver.requireAuthenticated(request);
        enforceProxyAccess(request, legacyUser);
        ragProxyService.forward(request, response, resolveBearerToken(legacyUser));
    }

    private String resolveBearerToken(com.tap.backend.academic.entity.UserEntity legacyUser) {
        if (legacyUser == null || legacyUser.getUsername() == null || legacyUser.getUsername().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "RAG proxy authentication required");
        }
        UserEntity user = userRepository.findByUsername(legacyUser.getUsername().trim()).orElse(null);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "current login user cannot be mapped to RAG token");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "current login user is disabled");
        }
        return issueRagJwt(user);
    }

    private String issueRagJwt(UserEntity user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(jwtProperties.accessTokenTtlSeconds());
        return Jwts.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .signWith(ragJwtKey, SignatureAlgorithm.HS256)
                .compact();
    }

    private void enforceProxyAccess(
            HttpServletRequest request,
            com.tap.backend.academic.entity.UserEntity legacyUser
    ) {
        if (!requiresTeacherOrAdmin(request)) {
            return;
        }
        if (legacyUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "RAG proxy authentication required");
        }
        String role = normalizeRole(legacyUser.getRole());
        if (!UserRole.ADMIN.name().equals(role) && !UserRole.TEACHER.name().equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teacher or admin role required for this RAG operation");
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

    private String normalizeRole(String role) {
        if (role == null) {
            return "";
        }
        return role.trim().toUpperCase();
    }
}
