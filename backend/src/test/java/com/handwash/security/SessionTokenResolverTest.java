package com.handwash.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionTokenResolverTest {
    private final SessionTokenResolver resolver = new SessionTokenResolver();

    @Test
    void resolvesStandardBearerSchemeCaseInsensitively() {
        assertEquals("secret-token", resolver.resolve("bEaReR secret-token", null));
    }

    @Test
    void keepsLegacySessionHeaderCompatible() {
        assertEquals("legacy-token", resolver.resolve(null, "legacy-token"));
    }

    @Test
    void acceptsMatchingCredentialsInBothHeaders() {
        assertEquals("same-token", resolver.resolve("Bearer same-token", "same-token"));
    }

    @Test
    void rejectsConflictingCredentialsInsteadOfChoosingOne() {
        assertNull(resolver.resolve("Bearer first-token", "second-token"));
    }

    @Test
    void rejectsMalformedOrEmptyAuthorizationHeaders() {
        assertNull(resolver.resolve("Basic secret-token", "legacy-token"));
        assertNull(resolver.resolve("Bearer", "legacy-token"));
        assertNull(resolver.resolve("Bearer token with-space", null));
        assertNull(resolver.resolve("   ", null));
        assertNull(resolver.resolve("   ", "legacy-token"));
    }
}
