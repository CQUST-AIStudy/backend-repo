package com.tap.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import com.tap.backend.repo.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
  private final JwtService jwtService;
  private final UserRepository userRepository;

  public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
    this.jwtService = jwtService;
    this.userRepository = userRepository;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }
    String token = header.substring("Bearer ".length()).trim();
    try {
      UserPrincipal principal = jwtService.parse(token);
      boolean enabled = userRepository.findById(principal.userId())
          .map(user -> Boolean.TRUE.equals(user.getEnabled()))
          .orElse(false);
      if (!enabled) {
        SecurityContextHolder.clearContext();
        filterChain.doFilter(request, response);
        return;
      }
      var auth = new UsernamePasswordAuthenticationToken(
          principal,
          null,
          List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
      );
      SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (Exception e) {
      SecurityContextHolder.clearContext();
    }
    filterChain.doFilter(request, response);
  }
}
