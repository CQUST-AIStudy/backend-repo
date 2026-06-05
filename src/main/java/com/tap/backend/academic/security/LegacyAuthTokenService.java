package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class LegacyAuthTokenService {
    public static final String COOKIE_NAME = "tap_legacy_auth";

    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final JwtProperties jwtProperties;
    private final SecretKey key;

    public LegacyAuthTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public void writeCookie(HttpServletResponse response, UserEntity user) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, issue(user))
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(TOKEN_TTL)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public Optional<UserEntity> resolve(HttpServletRequest request) {
        String token = readCookie(request);
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UserEntity user = new UserEntity();
            user.setId(claimAsInt(claims.get("uid")));
            user.setUsername(claims.getSubject());
            user.setRole(claims.get("role", String.class));
            user.setUsernum(claims.get("usernum", String.class));
            user.setClassname(claims.get("classname", String.class));
            return Optional.of(user);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String issue(UserEntity user) {
        Instant now = Instant.now();
        Instant exp = now.plus(TOKEN_TTL);
        return Jwts.builder()
                .issuer(issuer())
                .subject(user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("uid", user.getId())
                .claim("role", user.getRole())
                .claim("usernum", user.getUsernum())
                .claim("classname", user.getClassname())
                .signWith(key)
                .compact();
    }

    private String issuer() {
        return jwtProperties.issuer() + "-legacy";
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private int claimAsInt(Object value) {
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
