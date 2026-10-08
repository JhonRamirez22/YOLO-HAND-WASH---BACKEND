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
    private final String[] allowedOrigins;

    public WebSocketConfig(
        HandWashWebSocketHandler handler,
        @Value("${handwash.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}")
        String allowedOrigins
    ) {
        this.handler = handler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim).filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/{sessionId}")
                .setAllowedOrigins(allowedOrigins);
    }
}
