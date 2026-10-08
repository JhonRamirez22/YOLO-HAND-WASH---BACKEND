package com.handwash.security;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SessionPairingCodeRegistryTest {
    private static final Pattern DISPLAY_CODE = Pattern.compile(
        "^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}$");

    @Test
    void generatesUniqueCodesAndResolvesSupportedInputFormats() {
        SessionPairingCodeRegistry registry = new SessionPairingCodeRegistry();
        String firstSession = "session-1";
        String secondSession = "session-2";

        String firstCode = registry.register(firstSession);
        String secondCode = registry.register(secondSession);

        assertTrue(DISPLAY_CODE.matcher(firstCode).matches());
        assertTrue(DISPLAY_CODE.matcher(secondCode).matches());
        assertNotEquals(firstCode, secondCode);
        assertEquals(firstCode, registry.displayCode(firstSession));
        assertEquals(firstSession, registry.findSessionId(firstCode.toLowerCase(Locale.ROOT)));
        assertEquals(firstSession, registry.findSessionId(firstCode.replace("-", " ")));
        assertNull(registry.findSessionId("invalid"));
        assertNull(registry.findSessionId(null));
    }

    @Test
    void removalRevokesBothLookupDirections() {
        SessionPairingCodeRegistry registry = new SessionPairingCodeRegistry();
        String sessionId = "session-1";
        String code = registry.register(sessionId);

        registry.remove(sessionId);

        assertNull(registry.displayCode(sessionId));
        assertNull(registry.findSessionId(code));
        assertDoesNotThrow(() -> registry.remove(sessionId));
    }

    @Test
    void rejectsBlankAndDuplicateSessionRegistration() {
        SessionPairingCodeRegistry registry = new SessionPairingCodeRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register(" "));
        registry.register("session-1");
        assertThrows(IllegalStateException.class, () -> registry.register("session-1"));
    }
}
