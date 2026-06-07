package com.tap.backend.security;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
public class StudentPrincipalResolver {
  private final UserRepository userRepository;

  @PersistenceContext
  private EntityManager em;

  public StudentPrincipalResolver(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public ResolvedStudent requireStudent(UserPrincipal principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    var user = userRepository.findById(principal.userId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
    if (user.getRole() != UserRole.STUDENT) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student role required");
    }
    String studentNum = resolveStudentNum(user.getUsername());
    if (studentNum == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student id missing");
    }
    return new ResolvedStudent(user.getId(), user.getUsername(), user.getDisplayName(), resolveCanonicalStudentNo(studentNum));
  }

  @Transactional
  public ResolvedStudent requireStudent(com.tap.backend.academic.entity.UserEntity legacyUser) {
    if (legacyUser == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    if (!"student".equals(normalize(legacyUser.getRole()))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student role required");
    }

    String username = normalize(legacyUser.getUsername());
    if (username == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student username missing");
    }
    String studentNum = normalize(legacyUser.getUsernum());
    if (studentNum == null) {
      studentNum = resolveStudentNum(username);
    }
    if (studentNum == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student id missing");
    }

    UserEntity tapUser = userRepository.findByUsername(username).orElseGet(UserEntity::new);
    if (tapUser.getId() == null) {
      tapUser.setUsername(username);
      tapUser.setRole(UserRole.STUDENT);
      tapUser.setEnabled(true);
    }
    if (tapUser.getRole() != UserRole.STUDENT) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "student role required");
    }
    if (!Boolean.TRUE.equals(tapUser.getEnabled())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user account is disabled");
    }
    if (normalize(tapUser.getDisplayName()) == null) {
      tapUser.setDisplayName(username);
    }
    tapUser = userRepository.save(tapUser);

    return new ResolvedStudent(tapUser.getId(), tapUser.getUsername(), tapUser.getDisplayName(), resolveCanonicalStudentNo(studentNum));
  }

  public String requireStudentId(UserPrincipal principal) {
    return requireStudent(principal).studentNum();
  }

  public String requireAuthorizedStudentId(String requestedStudentId, UserPrincipal principal) {
    ResolvedStudent student = requireStudent(principal);
    String requestedValue = normalizeStudentId(requestedStudentId);
    if (!student.studentNum().equals(requestedValue)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
    return student.studentNum();
  }

  private String resolveCanonicalStudentNo(String studentId) {
    @SuppressWarnings("unchecked")
    List<String> matchedStudentNumbers = em.createNativeQuery(
            "SELECT student_no FROM student_profile WHERE student_no = ?1 OR CAST(id AS CHAR) = ?1 " +
                "ORDER BY CASE WHEN student_no = ?1 THEN 0 ELSE 1 END LIMIT 1",
            String.class
        ).setParameter(1, studentId)
        .getResultList();
    return matchedStudentNumbers.isEmpty() ? studentId : matchedStudentNumbers.get(0);
  }

  private String resolveStudentNum(String username) {
    String legacyUsernum = findLegacyUsernum(username);
    if (legacyUsernum != null) {
      return legacyUsernum;
    }
    return normalizeStudentId(username);
  }

  private String findLegacyUsernum(String username) {
    String normalizedUsername = normalizeStudentId(username);
    if (normalizedUsername == null) {
      return null;
    }
    try {
      @SuppressWarnings("unchecked")
      List<String> usernums = em.createNativeQuery(
              "SELECT usernum FROM tap_user WHERE username = ?1 AND usernum IS NOT NULL AND TRIM(usernum) <> '' LIMIT 1",
              String.class
          ).setParameter(1, normalizedUsername)
          .getResultList();
      return usernums.stream()
          .map(this::normalizeStudentId)
          .filter(value -> value != null)
          .findFirst()
          .orElse(null);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private String normalizeStudentId(String value) {
    return normalize(value);
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  public record ResolvedStudent(Long userId, String username, String displayName, String studentNum) {}
}
