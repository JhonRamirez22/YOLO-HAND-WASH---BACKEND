package com.handwash.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Seam: who the client is behind CloudFront (adds the viewer) and the ALB (adds CloudFront's edge). */
class ClientAddressTest {
    @Test
    void takesTheEntryAddedByTheTrustedProxiesNotTheOneTheClientSupplied() {
        assertEquals("203.0.113.9", ClientAddress.resolve("203.0.113.9, 130.176.1.1", "10.0.12.7", 2));
        // A client that sends its own X-Forwarded-For cannot choose the address it is judged by.
        assertEquals("203.0.113.9", ClientAddress.resolve("1.2.3.4, 5.6.7.8, 203.0.113.9, 130.176.1.1", "10.0.12.7", 2));
        assertEquals("203.0.113.9", ClientAddress.resolve("  203.0.113.9 ,130.176.1.1 ", "10.0.12.7", 2));
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsMissingOrNotAnAddress() {
        assertEquals("10.0.12.7", ClientAddress.resolve(null, "10.0.12.7", 2));
        assertEquals("10.0.12.7", ClientAddress.resolve("", "10.0.12.7", 2));
        assertEquals("10.0.12.7", ClientAddress.resolve("unknown, 130.176.1.1", "10.0.12.7", 2));
        assertEquals("10.0.12.7", ClientAddress.resolve("'; DROP TABLE x;--, 130.176.1.1", "10.0.12.7", 2));
        assertEquals("10.0.12.7", ClientAddress.resolve("a".repeat(500), "10.0.12.7", 2));
    }

    @Test
    void acceptsIpv6AndAShorterChain() {
        assertEquals("2001:db8::1", ClientAddress.resolve("2001:db8::1, 130.176.1.1", "10.0.12.7", 2));
        assertEquals("203.0.113.9", ClientAddress.resolve("203.0.113.9", "10.0.12.7", 2));
    }

    @Test
    void theFilterStoresTheResolvedAddressAndCallersFallBackToThePeerWithoutIt() throws Exception {
        var request = new MockHttpServletRequest("POST", "/x");
        request.setRemoteAddr("10.0.12.7");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9, 130.176.1.1");

        assertEquals("10.0.12.7", ClientAddress.of(request), "no filter yet: the peer");
        new ClientAddressFilter(2).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertEquals("203.0.113.9", ClientAddress.of(request));
    }
}
