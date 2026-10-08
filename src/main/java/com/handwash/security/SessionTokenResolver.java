package com.handwash.security;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the standard Bearer credential and the legacy local-client header. */
@Component
public final class SessionTokenResolver {
    private static final Pattern BEARER = Pattern.compile("(?i)^Bearer\\s+([^\\s]+)$");

    /**
     * Returns the supplied token, or {@code null} when no usable, unambiguous
     * credential was supplied. A malformed Authorization header never falls
     * back to the legacy header.
     */
    public String resolve(String authorizationHeader, String legacySessionToken) {
        boolean hasAuthorization = authorizationHeader != null;
        boolean hasLegacyToken = legacySessionToken != null && !legacySessionToken.isBlank();

        if (!hasAuthorization) return hasLegacyToken ? legacySessionToken : null;

        Matcher matcher = BEARER.matcher(authorizationHeader.trim());
        if (!matcher.matches()) return null;
        String bearerToken = matcher.group(1);

        if (hasLegacyToken && !bearerToken.equals(legacySessionToken)) return null;
        return bearerToken;
    }
}
