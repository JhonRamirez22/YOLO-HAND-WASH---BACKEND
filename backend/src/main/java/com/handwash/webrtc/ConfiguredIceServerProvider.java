package com.handwash.webrtc;

import com.handwash.security.WebRtcTicketRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * STUN from configuration plus TURN with short-lived credentials in the coturn REST scheme
 * ({@code use-auth-secret}): username {@code <expiry>:<session>:<role>}, credential
 * {@code base64(HMAC-SHA1(secret, username))}. The shared secret never leaves Java.
 * Without configuration the list is empty and behaviour is unchanged.
 */
@Component
public final class ConfiguredIceServerProvider implements IceServerProvider {
    static final int MAX_URLS = 4;
    static final long MIN_TTL_SECONDS = 300;
    static final long MAX_TTL_SECONDS = 86_400;
    static final int MIN_SECRET_CHARS = 32;
    private final List<String> stunUrls;
    private final List<String> turnUrls;
    private final byte[] secret;
    private final long ttlSeconds;
    private final LongSupplier clockMillis;

    @Autowired
    public ConfiguredIceServerProvider(
        @Value("${handwash.webrtc.stun-urls:}") String stunUrls,
        @Value("${handwash.webrtc.turn-urls:}") String turnUrls,
        @Value("${handwash.webrtc.turn-shared-secret:}") String sharedSecret,
        @Value("${handwash.webrtc.turn-credential-ttl-seconds:3600}") long ttlSeconds) {
        this(split(stunUrls), split(turnUrls), sharedSecret, ttlSeconds, System::currentTimeMillis);
    }

    ConfiguredIceServerProvider(List<String> stunUrls, List<String> turnUrls, String sharedSecret,
                                long ttlSeconds, LongSupplier clockMillis) {
        this.stunUrls = validated(stunUrls, "stun:");
        this.turnUrls = validated(turnUrls, "turn:", "turns:");
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("handwash.webrtc.turn-credential-ttl-seconds debe estar entre 300 y 86400");
        }
        boolean hasSecret = sharedSecret != null && !sharedSecret.isBlank();
        if (!this.turnUrls.isEmpty() && (!hasSecret || sharedSecret.length() < MIN_SECRET_CHARS)) {
            throw new IllegalArgumentException(
                "TURN requiere handwash.webrtc.turn-shared-secret de al menos 32 caracteres");
        }
        this.secret = hasSecret ? sharedSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
        this.ttlSeconds = ttlSeconds;
        this.clockMillis = clockMillis;
    }

    @Override
    public List<IceServer> servers(String sessionId, WebRtcTicketRegistry.PeerRole role) {
        List<IceServer> servers = new ArrayList<>(2);
        if (!stunUrls.isEmpty()) servers.add(new IceServer(stunUrls, null, null));
        if (!turnUrls.isEmpty()) {
            long expiresAt = clockMillis.getAsLong() / 1000 + ttlSeconds;
            String username = expiresAt + ":" + sessionId + ":" + role.name();
            servers.add(new IceServer(turnUrls, username, credential(username)));
        }
        return List.copyOf(servers);
    }

    private String credential(String username) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return Base64.getEncoder().encodeToString(hmac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA1 no disponible", unavailable);
        }
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
            : Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static List<String> validated(List<String> urls, String... schemes) {
        if (urls.size() > MAX_URLS) throw new IllegalArgumentException("Máximo " + MAX_URLS + " URLs ICE por tipo");
        for (String url : urls) {
            boolean schemeOk = Arrays.stream(schemes).anyMatch(url::startsWith);
            String rest = schemeOk ? url.substring(url.indexOf(':') + 1) : "";
            if (!schemeOk || rest.isEmpty() || rest.length() > 200 || rest.contains("@")
                || rest.chars().anyMatch(c -> c <= ' ' || c > '~')) {
                throw new IllegalArgumentException("URL ICE no válida: debe usar " + String.join(" o ", schemes)
                    + " sin credenciales");
            }
        }
        return List.copyOf(urls);
    }
}
