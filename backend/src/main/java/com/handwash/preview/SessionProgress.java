package com.handwash.preview;

/** How far a wash has got according to Java: the step being credited (0 = none yet) and the one expected next (0 = finished). */
public record SessionProgress(int current, int expected) {
    public static final SessionProgress NONE = new SessionProgress(0, 1);
}
