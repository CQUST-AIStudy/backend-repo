package com.tap.backend.api.rag;

import com.tap.backend.academic.security.LegacySessionAccessResolver;
import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<?> proxyGet(HttpServletRequest request) {
        return forward(request);
    }

    @PostMapping({"", "/**"})
    public ResponseEntity<?> proxyPost(HttpServletRequest request) {
        return forward(request);
    }

    @PutMapping({"", "/**"})
    public ResponseEntity<?> proxyPut(HttpServletRequest request) {
        return forward(request);
    }

    @DeleteMapping({"", "/**"})
    public ResponseEntity<?> proxyDelete(HttpServletRequest request) {
        return forward(request);
    }

    private ResponseEntity<?> forward(HttpServletRequest request) {
        com.tap.backend.academic.entity.UserEntity legacyUser = legacySessionAccessResolver.requireAuthenticated(request);
        return ragProxyService.forward(request, resolveBearerToken(legacyUser));
    }

    private String resolveBearerToken(com.tap.backend.academic.entity.UserEntity legacyUser) {
        if (legacyUser == null || legacyUser.getUsername() == null || legacyUser.getUsername().isBlank()) {
            return null;
        }
        UserEntity user = userRepository.findByUsername(legacyUser.getUsername().trim()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return null;
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
}
