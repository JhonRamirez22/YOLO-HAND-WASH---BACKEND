package com.handwash.webrtc;

import java.util.List;

/** One RTCIceServer entry; username and credential are null for plain STUN. */
public record IceServer(List<String> urls, String username, String credential) {
    public IceServer { urls = List.copyOf(urls); }
}
