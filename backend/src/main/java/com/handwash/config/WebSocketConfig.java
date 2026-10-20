package com.handwash.config;

import com.handwash.websocket.HandWashWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final HandWashWebSocketHandler handler;
    private final com.handwash.websocket.WebRtcSignalingHandler webRtcHandler;
    private final com.handwash.preview.PreviewSocketHandler previewHandler;
    private final com.handwash.preview.CameraFrameIngressHandler cameraIn;
    private final com.handwash.preview.CameraFrameEgressHandler cameraOut;
    private final com.handwash.preview.DetectionIngressHandler detectionIngress;
    private final String[] allowedOrigins;

    public WebSocketConfig(
        HandWashWebSocketHandler handler,
        com.handwash.websocket.WebRtcSignalingHandler webRtcHandler,
        com.handwash.preview.PreviewSocketHandler previewHandler,
        com.handwash.preview.CameraFrameIngressHandler cameraIn,
        com.handwash.preview.CameraFrameEgressHandler cameraOut,
        com.handwash.preview.DetectionIngressHandler detectionIngress,
        @Value("${handwash.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}")
        String allowedOrigins
    ) {
        this.handler = handler;
        this.webRtcHandler = webRtcHandler;
        this.previewHandler = previewHandler;
        this.cameraIn = cameraIn;
        this.cameraOut = cameraOut;
        this.detectionIngress = detectionIngress;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim).filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/{sessionId}")
                .setAllowedOrigins(allowedOrigins);
        registry.addHandler(webRtcHandler, "/ws/webrtc/{sessionId}")
                .setAllowedOrigins(allowedOrigins);
        registry.addHandler(previewHandler, "/ws/preview/{sessionId}")
                .setAllowedOrigins(allowedOrigins);
        // The phone camera publishes its frames here (camera ticket); the RTX PC receives them and answers with its overlay.
        registry.addHandler(cameraIn, "/ws/camera-in/{sessionId}")
                .setAllowedOrigins(allowedOrigins);
        // The PC is not a browser: it connects without an Origin header, authenticated by its producer ticket.
        registry.addHandler(cameraOut, "/ws/camera-out/{sessionId}")
                .setAllowedOrigins("*");
        registry.addHandler(detectionIngress, "/ws/detections-in/{sessionId}")
                .setAllowedOrigins("*");
    }
}
