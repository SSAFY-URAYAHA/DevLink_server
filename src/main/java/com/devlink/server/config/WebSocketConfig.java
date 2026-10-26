package com.devlink.server.config;

import com.devlink.server.handler.DevLinkWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final DevLinkWebSocketHandler handler;
    private final String[] allowedOrigins;

    public WebSocketConfig(DevLinkWebSocketHandler handler,
            @Value("${devlink.websocket.allowed-origins}") String[] allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // TODO: 인증 방식 확정 후 핸드셰이크 인증 및 STOMP 사용 여부 결정.
        registry.addHandler(handler, "/ws").setAllowedOrigins(allowedOrigins);
    }
}
