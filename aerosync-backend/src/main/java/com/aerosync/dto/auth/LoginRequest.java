package com.aerosync.dto.auth;

/** Body of {@code POST /api/auth/login}. */
public record LoginRequest(String username, String password) {

    /** Never print the password, e.g. in debug logs of the request body. */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + "]";
    }
}
