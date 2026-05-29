package com.tap.backend.security;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final JwtProperties props;
  private final SecretKey key;

  public JwtService(JwtProperties props) {
    this.props = props;
    this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
  }

  public String issue(UserEntity user) {
    Instant now = Instant.now();
    Instant exp = now.plusSeconds(props.accessTokenTtlSeconds());
    return Jwts.builder()
        .issuer(props.issuer())
        .subject(user.getUsername())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .claim("uid", user.getId())
        .claim("role", user.getRole().name())
        .signWith(key)
        .compact();
  }

  public UserPrincipal parse(String token) {
    Claims claims = Jwts.parser()
        .verifyWith(key)
        .requireIssuer(props.issuer())
        .build()
        .parseSignedClaims(token)
        .getPayload();
    long uid = claims.get("uid", Number.class).longValue();
    String username = claims.getSubject();
    String role = claims.get("role", String.class);
    return new UserPrincipal(uid, username, UserRole.valueOf(role));
  }
}
