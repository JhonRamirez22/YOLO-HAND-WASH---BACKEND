package com.handwash.service;

/** Every profile change needs a human reason: 10 to 200 printable characters. */
public final class ChangeReason {
    public static final int MIN = 10;
    public static final int MAX = 200;

    private ChangeReason() {}

    public static String normalize(String reason) {
        if (reason == null) throw new IllegalArgumentException("MOTIVO_INVALIDO");
        String clean = reason.strip().replaceAll("\\s+", " ");
        if (clean.length() < MIN || clean.length() > MAX || clean.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("MOTIVO_INVALIDO");
        }
        return clean;
    }
}
