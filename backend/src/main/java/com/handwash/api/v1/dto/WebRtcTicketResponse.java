package com.handwash.api.v1.dto;

import com.handwash.webrtc.IceServer;

import java.util.List;

/** {@code iceServers} is additive: older clients that only read {@code ticket} keep working. */
public record WebRtcTicketResponse(String ticket, String peerRole, String expiresAt,
                                   List<IceServer> iceServers) {
    public WebRtcTicketResponse { iceServers = iceServers == null ? List.of() : List.copyOf(iceServers); }
}
