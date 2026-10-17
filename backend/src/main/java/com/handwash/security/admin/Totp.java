package com.handwash.security.admin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/** RFC 6238 time-based one-time passwords (SHA-1, 6 digits, 30 s): what Microsoft Authenticator and Google Authenticator expect. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final Pattern SIX_DIGITS = Pattern.compile("[0-9]{6}");
    private static final long STEP_SECONDS = 30;

    private Totp() {}

    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] counter = new byte[8];
            for (int i = 7, value = 0; i >= 0; i--) { counter[i] = (byte) (step >>> (8 * (7 - i))); }
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * @return the accepted step (current, previous or next) or -1. A step at or before {@code lastAcceptedStep} is a
     *         replay of a code already used and is refused.
     */
    public static long verify(byte[] secret, String presented, long nowEpochSeconds, long lastAcceptedStep) {
        if (presented == null || !SIX_DIGITS.matcher(presented).matches()) return -1;
        long current = nowEpochSeconds / STEP_SECONDS;
        long accepted = -1;
        for (long step = current - 1; step <= current + 1; step++) {
            boolean match = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
            if (match && step > lastAcceptedStep && accepted == -1) accepted = step;
        }
        return accepted;
    }

    public static String toBase32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) { out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return out.toString();
    }

    public static byte[] fromBase32(String text) {
        if (text == null || !text.matches("[A-Z2-7]+")) throw new IllegalArgumentException("Base32 inválido");
        byte[] out = new byte[text.length() * 5 / 8];
        int buffer = 0, bits = 0, index = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(c);
            bits += 5;
            if (bits >= 8) { out[index++] = (byte) (buffer >> (bits - 8)); bits -= 8; }
        }
        return out;
    }

    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = URLEncoder.encode(issuer, StandardCharsets.UTF_8) + ":" + URLEncoder.encode(account, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer="
            + URLEncoder.encode(issuer, StandardCharsets.UTF_8) + "&algorithm=SHA1&digits=6&period=30";
    }
}
