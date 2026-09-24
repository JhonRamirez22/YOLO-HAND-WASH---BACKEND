package com.handwash.webrtc;

import com.handwash.security.WebRtcTicketRegistry;

import java.util.List;

/**
 * Strategy that decides which STUN/TURN servers a peer may use. Media goes peer to peer
 * or through a TURN relay that only sees SRTP ciphertext; Java never touches it.
 */
public interface IceServerProvider {
    /** Servers for one peer of one session; empty means "LAN only", as before. */
    List<IceServer> servers(String sessionId, WebRtcTicketRegistry.PeerRole role);

    static IceServerProvider none() {
        return (sessionId, role) -> List.of();
    }
}
