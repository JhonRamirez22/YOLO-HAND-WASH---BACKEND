package com.handwash.security;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns unique, short-lived-in-practice pairing-code indexes for active sessions. */
public final class SessionPairingCodeRegistry {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;
    private static final int DISPLAY_GROUP_LENGTH = 5;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, String> codeBySession = new ConcurrentHashMap<>();
    private final Map<String, String> sessionByCode = new ConcurrentHashMap<>();

    /** Registers a fresh code and returns its display form (XXXXX-XXXXX). */
    public synchronized String register(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId es obligatorio para crear el código");
        }
        if (codeBySession.containsKey(sessionId)) {
            throw new IllegalStateException("La sesión ya tiene un código de vinculación");
        }

        String code;
        do {
            code = generateCode();
        } while (sessionByCode.putIfAbsent(code, sessionId) != null);
        codeBySession.put(sessionId, code);
        return format(code);
    }

    public String displayCode(String sessionId) {
        String code = codeBySession.get(sessionId);
        return code == null ? null : format(code);
    }

    /** Normalizes the existing accepted forms: spaces, hyphen and lowercase. */
    public String findSessionId(String suppliedCode) {
        if (suppliedCode == null) return null;
        String normalized = suppliedCode.trim().toUpperCase(Locale.ROOT)
            .replace("-", "").replace(" ", "");
        if (normalized.length() != CODE_LENGTH) return null;
        return sessionByCode.get(normalized);
    }

    public synchronized void remove(String sessionId) {
        if (sessionId == null) return;
        String code = codeBySession.remove(sessionId);
        if (code != null) sessionByCode.remove(code, sessionId);
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private String format(String code) {
        return code.substring(0, DISPLAY_GROUP_LENGTH) + "-" + code.substring(DISPLAY_GROUP_LENGTH);
    }
}
