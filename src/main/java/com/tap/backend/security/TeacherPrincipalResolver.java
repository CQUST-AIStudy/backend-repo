package com.tap.backend.security;

import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class TeacherPrincipalResolver {
  private final UserRepository userRepository;

  public TeacherPrincipalResolver(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public Long requireTeacherId(UserPrincipal principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    var user = userRepository.findById(principal.userId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
    if (user.getRole() != UserRole.TEACHER && user.getRole() != UserRole.ADMIN) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teacher role required");
    }
    return user.getId();
  }
}
