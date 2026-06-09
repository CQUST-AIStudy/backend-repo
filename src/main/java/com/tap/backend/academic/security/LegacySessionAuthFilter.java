package com.tap.backend.academic.security;

import com.tap.backend.academic.entity.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import com.tap.backend.security.UserPrincipal;
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
    private final LegacyAuthTokenService legacyAuthTokenService;
    private final UserRepository userRepository;

    public LegacySessionAuthFilter(LegacyAuthTokenService legacyAuthTokenService, UserRepository userRepository) {
        this.legacyAuthTokenService = legacyAuthTokenService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object currentUser = session.getAttribute("currentUser");
                if (currentUser instanceof UserEntity user) {
                    authenticate(user);
                }
            }
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                var cookieUser = legacyAuthTokenService.resolve(request);
                if (cookieUser.isPresent()) {
                    authenticate(cookieUser.get());
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(UserEntity user) {
        String role = normalizeRole(user.getRole());
        if (role == null) {
            return;
        }
        Object principal = resolveTapPrincipal(user, role);
        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Object resolveTapPrincipal(UserEntity user, String normalizedRole) {
        String username = user.getUsername() == null ? null : user.getUsername().trim();
        if (username == null || username.isEmpty()) {
            return user;
        }
        try {
            UserRole role = UserRole.valueOf(normalizedRole);
            return userRepository.findByUsername(username)
                    .filter(tapUser -> Boolean.TRUE.equals(tapUser.getEnabled()))
                    .<Object>map(tapUser -> new UserPrincipal(tapUser.getId(), tapUser.getUsername(), role))
                    .orElse(user);
        } catch (RuntimeException e) {
            return user;
        }
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
