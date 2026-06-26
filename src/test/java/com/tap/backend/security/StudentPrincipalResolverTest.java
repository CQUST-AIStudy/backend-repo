package com.tap.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tap.backend.domain.user.UserEntity;
import com.tap.backend.domain.user.UserRole;
import com.tap.backend.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StudentPrincipalResolverTest {

  @Test
  void resolvesStudentNumberFromStudentProfileUserIdWhenTapUserUsernumIsMissing() {
    UserRepository userRepository = mock(UserRepository.class);
    EntityManager em = mock(EntityManager.class);
    Query usernumQuery = queryReturning(List.of());
    Query profileByUserIdQuery = queryReturning(List.of("20230001"));
    Query canonicalQuery = queryReturning(List.of("20230001"));

    when(em.createNativeQuery(anyString(), eq(String.class)))
        .thenReturn(usernumQuery, profileByUserIdQuery, canonicalQuery);

    UserEntity user = new UserEntity();
    ReflectionTestUtils.setField(user, "id", 2L);
    user.setUsername("student1");
    user.setRole(UserRole.STUDENT);
    user.setEnabled(true);
    user.setUsernum(null);
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));

    StudentPrincipalResolver resolver = new StudentPrincipalResolver(userRepository);
    ReflectionTestUtils.setField(resolver, "em", em);

    StudentPrincipalResolver.ResolvedStudent resolved =
        resolver.requireStudent(new UserPrincipal(2L, "student1", UserRole.STUDENT));

    assertEquals("20230001", resolved.studentNum());
  }

  private Query queryReturning(List<?> values) {
    Query query = mock(Query.class);
    when(query.setParameter(eq(1), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    when(query.getResultList()).thenReturn(values);
    return query;
  }
}
