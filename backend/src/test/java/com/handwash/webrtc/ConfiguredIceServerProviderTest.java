package com.handwash.webrtc;

import com.handwash.security.WebRtcTicketRegistry.PeerRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredIceServerProviderTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef-secret";
    private static final long NOW_MS = 1_792_600_000_000L;
    private static final List<String> TURN = List.of("turn:turn.example.org:3478?transport=udp",
        "turns:turn.example.org:443?transport=tcp");

    private static ConfiguredIceServerProvider provider(List<String> stun, List<String> turn, String secret, long ttl) {
        return new ConfiguredIceServerProvider(stun, turn, secret, ttl, () -> NOW_MS);
    }

    @Test
    void unconfiguredMeansLanOnlyAsBefore() {
        assertTrue(provider(List.of(), List.of(), "", 3600).servers("s", PeerRole.BROWSER).isEmpty());
        assertTrue(IceServerProvider.none().servers("s", PeerRole.RTX_PRODUCER).isEmpty());
    }

    @Test
    void stunHasNoCredentials() {
        var servers = provider(List.of("stun:stun.example.org:3478"), List.of(), "", 3600).servers("s", PeerRole.BROWSER);

        assertEquals(1, servers.size());
        assertEquals(List.of("stun:stun.example.org:3478"), servers.get(0).urls());
        assertNull(servers.get(0).username());
        assertNull(servers.get(0).credential());
    }

    @Test
    void turnCredentialsMatchTheCoturnRestSchemeAgainstAnIndependentHmac() {
        var provider = provider(List.of(), TURN, SECRET, 3600);

        IceServer browser = provider.servers("session-1", PeerRole.BROWSER).get(0);
        IceServer producer = provider.servers("session-1", PeerRole.RTX_PRODUCER).get(0);

        assertEquals(TURN, browser.urls());
        // Expected values computed outside Java: base64(HMAC-SHA1(secret, username)), expiry = now + 3600 s.
        assertEquals("1792603600:session-1:BROWSER", browser.username());
        assertEquals("QzawLGJBJzPJUWPuqEEio+oN7DQ=", browser.credential());
        assertEquals("1792603600:session-1:RTX_PRODUCER", producer.username());
        assertEquals("YbdsUcRTpqPwZYlMWHpY9venm94=", producer.credential());
    }

    @Test
    void stunAndTurnAreReturnedTogetherAndTheSecretNeverAppears() {
        var servers = provider(List.of("stun:stun.example.org:3478"), TURN, SECRET, 600).servers("s", PeerRole.BROWSER);

        assertEquals(2, servers.size());
        assertTrue(servers.stream().noneMatch(server -> String.valueOf(server).contains(SECRET)));
    }

    @Test
    void rejectsUnsafeOrIncompleteConfigurationAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> provider(List.of(), TURN, "", 3600), "TURN without secret");
        assertThrows(IllegalArgumentException.class, () -> provider(List.of(), TURN, "short", 3600), "weak secret");
        assertThrows(IllegalArgumentException.class, () -> provider(List.of("http://stun.example.org"), List.of(), "", 3600));
        assertThrows(IllegalArgumentException.class,
            () -> provider(List.of(), List.of("turn:user:pass@turn.example.org"), SECRET, 3600), "credentials inside the URL");
        assertThrows(IllegalArgumentException.class, () -> provider(List.of("stun:a b"), List.of(), "", 3600));
        assertThrows(IllegalArgumentException.class, () -> provider(
            List.of("stun:1", "stun:2", "stun:3", "stun:4", "stun:5"), List.of(), "", 3600));
        assertThrows(IllegalArgumentException.class, () -> provider(List.of(), TURN, SECRET, 60), "ttl too short");
        assertThrows(IllegalArgumentException.class, () -> provider(List.of(), TURN, SECRET, 100_000), "ttl too long");
    }

    @Test
    void springConstructorParsesCommaSeparatedValues() {
        var provider = new ConfiguredIceServerProvider(" stun:a.example.org:3478 , stun:b.example.org:3478 ",
            "turns:turn.example.org:443?transport=tcp", SECRET, 3600);

        var servers = provider.servers("s", PeerRole.BROWSER);
        assertEquals(List.of("stun:a.example.org:3478", "stun:b.example.org:3478"), servers.get(0).urls());
        assertEquals(List.of("turns:turn.example.org:443?transport=tcp"), servers.get(1).urls());
    }
}
