package com.aerosync.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogRedactionTest {

    @Test
    void removesCredentialsFromUrlsAndHeaders() {
        assertEquals("GET https://api.example.com/weather?q=Mumbai&appid=***&units=metric failed",
                LogRedaction.redact("GET https://api.example.com/weather?q=Mumbai&appid=abc123&units=metric failed"));
        assertEquals("POST /v1beta/models/x:generateContent?key=*** 403",
                LogRedaction.redact("POST /v1beta/models/x:generateContent?key=AIzaSecret 403"));
        assertEquals("Authorization: Bearer ***", LogRedaction.redact("Authorization: Bearer eyJhbGciOi.x.y"));
        assertEquals("plain message", LogRedaction.redact("plain message"));
        assertNull(LogRedaction.redact(null));
    }
}
