package com.handwash.security.admin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seam: Totp. Expected values are the official RFC 6238 SHA-1 test vectors (last six digits), not recomputed here. */
class TotpTest {
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void producesTheRfc6238VectorsAtThe30SecondStep() {
        assertEquals("287082", Totp.code(RFC_SECRET, 59L / 30));
        assertEquals("081804", Totp.code(RFC_SECRET, 1111111109L / 30));
        assertEquals("050471", Totp.code(RFC_SECRET, 1111111111L / 30));
        assertEquals("005924", Totp.code(RFC_SECRET, 1234567890L / 30));
        assertEquals("279037", Totp.code(RFC_SECRET, 2000000000L / 30));
        assertEquals("353130", Totp.code(RFC_SECRET, 20000000000L / 30));
    }

    @Test
    void verifyAcceptsTheCurrentAndAdjacentStepsOnlyAndReturnsTheStepUsed() {
        long now = 1234567890L;
        long step = now / 30;
        assertEquals(step, Totp.verify(RFC_SECRET, "005924", now, 0L));
        assertEquals(step - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0L));
        assertEquals(step + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 0L));
        assertEquals(-1L, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, 0L));
        assertEquals(-1L, Totp.verify(RFC_SECRET, "000000", now, 0L));
    }

    @Test
    void aCodeAtOrBeforeTheLastAcceptedStepIsAReplayAndIsRefused() {
        long now = 1234567890L;
        long step = now / 30;
        assertEquals(-1L, Totp.verify(RFC_SECRET, "005924", now, step));
        assertEquals(-1L, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, step));
        assertEquals(step + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, step));
    }

    @Test
    void malformedCodesNeverMatch() {
        for (String bad : new String[] {null, "", "12345", "1234567", "12345a", " 005924", "00 5924"}) {
            assertEquals(-1L, Totp.verify(RFC_SECRET, bad, 1234567890L, 0L), String.valueOf(bad));
        }
    }

    @Test
    void base32RoundTripsAndRejectsGarbage() {
        byte[] secret = Totp.newSecret();
        assertEquals(20, secret.length);
        String text = Totp.toBase32(secret);
        assertTrue(text.matches("[A-Z2-7]{32}"));
        assertArrayEquals(secret, Totp.fromBase32(text));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.toBase32(RFC_SECRET));
        assertThrows(IllegalArgumentException.class, () -> Totp.fromBase32("not*base32"));
        assertFalse(Totp.toBase32(Totp.newSecret()).equals(Totp.toBase32(Totp.newSecret())));
    }

    @Test
    void theAuthenticatorUriCarriesTheSecretIssuerAndAccount() {
        String uri = Totp.otpauthUri("HandWash", "admin", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertTrue(uri.startsWith("otpauth://totp/HandWash:admin?"));
        assertTrue(uri.contains("secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"));
        assertTrue(uri.contains("issuer=HandWash"));
        assertTrue(uri.contains("digits=6") && uri.contains("period=30"));
    }
}
