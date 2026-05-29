package com.tap.backend.security;

import com.tap.backend.domain.user.UserRole;

public record UserPrincipal(
    long userId,
    String username,
    UserRole role
) {}
