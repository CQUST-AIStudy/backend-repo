package com.tap.backend.academic.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tap.backend.academic.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StudentSessionResolverTest {

    @Mock LegacySessionAccessResolver legacySessionAccessResolver;
    @Mock EntityManager entityManager;
    @Mock Query query;
    @Mock HttpServletRequest request;

    private StudentSessionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StudentSessionResolver(legacySessionAccessResolver);
        ReflectionTestUtils.setField(resolver, "em", entityManager);
    }

    @Test
    void resolvesProfilePrimaryKeyFromLoggedInStudentNumber() {
        UserEntity user = new UserEntity();
        user.setId(12);
        user.setRole("student");
        user.setUsernum("202344432");
        when(legacySessionAccessResolver.requireAuthenticated(request)).thenReturn(user);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(37L));

        Integer profileId = resolver.requireStudentProfileId(request);

        assertEquals(37, profileId);
        verify(query).setParameter(2, "202344432");
    }

    @Test
    void acceptsActiveMembershipForSelectedCourseClass() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1L);

        resolver.requireActiveClassMembership(37, 8L);

        verify(query).setParameter(1, 37);
        verify(query).setParameter(2, 8L);
    }
}
