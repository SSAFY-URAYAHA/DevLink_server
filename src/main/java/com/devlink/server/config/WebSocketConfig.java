package com.devlink.server.config;

import com.devlink.server.handler.DevLinkWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
// 브로커 하트비트 시간 초과 검사를 주기적으로 실행한다.
@EnableScheduling
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
        // Agent authentication is performed by the dedicated broker security chain.
        registry.addHandler(handler, "/ws/broker").setAllowedOrigins(allowedOrigins);
    }
}
