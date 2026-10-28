package com.devlink.server.realtime;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** READY·PONG·작업 알림이 같은 소켓에 동시에 쓰이지 않게 하는 공통 전송 경로다. */
@Component
public class BrokerMessageSender {
    private final ConnectionRegistry connections;
    private final ObjectMapper mapper;

    public BrokerMessageSender(ConnectionRegistry connections, ObjectMapper mapper) {
        this.connections = connections;
        this.mapper = mapper;
    }

    public boolean sendIfCurrent(String agentId, WebSocketSession session, Object event) throws IOException {
        return sendIfCurrent(agentId, session, event, () -> { });
    }

    /** ACK 등록처럼 현재 연결을 확인한 뒤 소켓 전송 직전에 실행할 동작을 받는다. */
    public boolean sendIfCurrent(String agentId, WebSocketSession session, Object event, Runnable beforeSend)
            throws IOException {
        TextMessage message = new TextMessage(mapper.writeValueAsString(event));
        // 네트워크 전송 중에는 Registry의 맵 잠금을 잡지 않음
        synchronized (session) {
            if (!connections.isCurrentConnection(agentId, session)) {
                return false;
            }
            // PONG 등 다른 메시지의 전송을 기다린 시간은 ACK 제한 시간에서 제외
            beforeSend.run();
            session.sendMessage(message);
            return true;
        }
    }
}
