package com.handwash.preview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends to one socket without ever queueing: while a send is in flight only the newest frame and the newest overlay
 * are kept. A slow client sees fewer frames, never old ones, so the delay cannot grow. Overlays go before frames.
 */
final class LatestWinsSocket implements CameraFrameHub.Sink {
    private static final Logger log = LoggerFactory.getLogger(LatestWinsSocket.class);
    private static final long SEND_TIMEOUT_MS = 2_000L;

    private final WebSocketSession socket;
    private final AtomicBoolean sending = new AtomicBoolean();
    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();
    private final AtomicReference<String> latestText = new AtomicReference<>();

    LatestWinsSocket(WebSocketSession socket) { this.socket = socket; }

    @Override public void binary(byte[] data) { if (socket.isOpen()) { latestFrame.set(data); drain(); } }

    @Override public void text(String json) { if (socket.isOpen()) { latestText.set(json); drain(); } }

    private void drain() {
        if (!sending.compareAndSet(false, true)) return;
        String text = latestText.getAndSet(null);
        byte[] frame = text == null ? latestFrame.getAndSet(null) : null;
        if (text == null && frame == null) { sending.set(false); return; }
        try {
            jakarta.websocket.Session nativeSession = socket instanceof NativeWebSocketSession wrapper
                ? wrapper.getNativeSession(jakarta.websocket.Session.class) : null;
            if (nativeSession != null) {
                nativeSession.getAsyncRemote().setSendTimeout(SEND_TIMEOUT_MS);
                jakarta.websocket.SendHandler done = result -> {
                    sending.set(false);
                    if (!result.isOK()) closeQuietly();
                    else if (latestText.get() != null || latestFrame.get() != null) drain();
                };
                if (text != null) nativeSession.getAsyncRemote().sendText(text, done);
                else nativeSession.getAsyncRemote().sendBinary(ByteBuffer.wrap(frame), done);
            } else {
                socket.sendMessage(text != null ? new TextMessage(text) : new BinaryMessage(frame));
                sending.set(false);
                if (latestText.get() != null || latestFrame.get() != null) drain();
            }
        } catch (IOException | RuntimeException error) {
            sending.set(false);
            log.debug("No se pudo enviar al cliente de cámara: {}", error.getClass().getSimpleName());
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try { if (socket.isOpen()) socket.close(CloseStatus.SESSION_NOT_RELIABLE); }
        catch (IOException | RuntimeException ignored) { /* already gone */ }
    }
}
