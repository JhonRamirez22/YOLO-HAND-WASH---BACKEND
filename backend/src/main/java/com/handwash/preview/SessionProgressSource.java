package com.handwash.preview;

/** Port: where the camera channels read Java's own view of a wash. */
public interface SessionProgressSource {
    SessionProgress of(String sessionId);
}
