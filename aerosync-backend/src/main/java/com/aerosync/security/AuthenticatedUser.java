package com.aerosync.security;

import com.aerosync.exception.AccessForbiddenException;
import com.aerosync.model.UserRole;

import java.util.Objects;
import java.util.UUID;

/**
 * The user behind a request, as loaded from the database by {@link JwtFilter}
 * after the token was verified (so a disabled account or a removed airport
 * takes effect on the next request, not when the token expires).
 *
 * <p>Controllers receive it as a method parameter (see
 * {@link CurrentUserArgumentResolver}).</p>
 */
public record AuthenticatedUser(UUID userId, String username, String displayName, UserRole role, AirportScope scope) {

    /** Request attribute under which {@link JwtFilter} stores the principal. */
    public static final String REQUEST_ATTRIBUTE = AuthenticatedUser.class.getName();

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(scope, "scope");
    }

    public boolean canOperate() {
        return role.canOperate();
    }

    /** Throws 403 unless the role may act (not only view). */
    public void requireOperatorRole() {
        if (!canOperate()) {
            throw new AccessForbiddenException("Your role " + role.name()
                    + " is read-only; this action needs " + UserRole.OPERATIONS_CONTROLLER.name());
        }
    }
}
