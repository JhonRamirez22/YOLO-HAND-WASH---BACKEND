package com.handwash.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * The address a request is judged by (rate limits, key throttling). Behind CloudFront and the ALB the peer is a
 * load balancer, and the first X-Forwarded-For entry is whatever the caller wrote, so the entry added by the
 * last trusted proxy is used instead: with N trusted hops that is the N-th entry from the right.
 */
public final class ClientAddress {
    static final String ATTRIBUTE = "handwash.clientAddress";
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientAddress() {}

    public static String of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String resolved ? resolved : request.getRemoteAddr();
    }

    static String resolve(String forwardedFor, String peer, int trustedHops) {
        if (forwardedFor == null || forwardedFor.isBlank() || forwardedFor.length() > 400 || trustedHops < 1) return peer;
        String[] entries = forwardedFor.split(",");
        String candidate = entries[Math.max(0, entries.length - trustedHops)].trim();
        return ADDRESS.matcher(candidate).matches() && (candidate.contains(".") || candidate.contains(":"))
            ? candidate : peer;
    }
}
