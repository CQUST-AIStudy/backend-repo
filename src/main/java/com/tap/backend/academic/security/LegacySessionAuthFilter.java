package com.tap.backend.academic.security;

import com.tap.backend.academic.dao.UserDao;
import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LegacySessionAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDao userDao;

    public LegacySessionAuthFilter(JwtService jwtService, UserDao userDao) {
        this.jwtService = jwtService;
        this.userDao = userDao;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            UserEntity user = resolveSessionUser(request);
            if (user == null) {
                user = resolveBearerUser(request);
            }
            if (user != null) {
                authenticate(user);
            }
        }
        filterChain.doFilter(request, response);
    }

    private UserEntity resolveSessionUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object currentUser = session.getAttribute("currentUser");
        return currentUser instanceof UserEntity user ? user : null;
    }

    private UserEntity resolveBearerUser(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            var principal = jwtService.parse(token);
            return userDao.findByUsername(principal.username());
        } catch (Exception ignored) {
            return null;
        }
    }

    private void authenticate(UserEntity user) {
        String role = normalizeRole(user.getRole());
        if (role == null) {
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return null;
        }
        String normalized = role.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toUpperCase();
    }
}
