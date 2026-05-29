package com.tap.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the authenticated principal for JWT-protected TAP endpoints.
 */
@Component
public class PrincipalResolver {
    public UserPrincipal resolve(UserPrincipal principal) {
        if (principal != null) {
            return principal;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
}
