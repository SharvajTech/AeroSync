package com.aerosync.security;

import java.util.Optional;
import java.util.UUID;

/** Loads the current state of a user named by a verified token. */
@FunctionalInterface
public interface PrincipalLookup {

    /** The user if the account exists and is enabled; otherwise empty. */
    Optional<AuthenticatedUser> findActivePrincipal(UUID userId);
}
