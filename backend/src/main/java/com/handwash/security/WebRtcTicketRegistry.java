package com.handwash.security;

import com.handwash.service.SessionManager;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class WebRtcTicketRegistry {
    private static final long TTL_MS = 30_000L;
    private static final int MAX_PENDING = 1_024;
    public enum PeerRole { BROWSER, RTX_PRODUCER }
    public record Grant(String sessionId, PeerRole role, SessionManager.AccessInfo access,
                        long expiresAtMs) {}

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public synchronized Issued issue(String sessionId, PeerRole role, SessionManager.AccessInfo access) {
        grants.entrySet().removeIf(entry -> System.currentTimeMillis() >= entry.getValue().expiresAtMs());
        if (grants.size() >= MAX_PENDING) throw new IllegalStateException("WebRTC ticket capacity reached");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = System.currentTimeMillis() + TTL_MS;
        grants.put(ticket, new Grant(sessionId, role, access, expiresAt));
        return new Issued(ticket, expiresAt);
    }

    public Grant consume(String sessionId, String ticket) {
        if (sessionId == null || ticket == null) return null;
        Grant grant = grants.remove(ticket);
        if (grant == null || !sessionId.equals(grant.sessionId())
            || System.currentTimeMillis() >= grant.expiresAtMs()) return null;
        return grant;
    }

    public record Issued(String value, long expiresAtMs) {}
}
