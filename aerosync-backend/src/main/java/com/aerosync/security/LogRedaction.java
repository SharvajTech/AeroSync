package com.aerosync.security;

import java.util.regex.Pattern;

/** Removes credentials from text before it is logged (e.g. exception messages that contain a request URL). */
public final class LogRedaction {

    private static final Pattern SECRET_PARAMETER = Pattern.compile(
            "(?i)\\b(key|appid|api_key|apikey|access_token|token|password|secret)=([^&\\s\"']+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

    private LogRedaction() {
    }

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String withoutParameters = SECRET_PARAMETER.matcher(text).replaceAll("$1=***");
        return BEARER.matcher(withoutParameters).replaceAll("Bearer ***");
    }
}
