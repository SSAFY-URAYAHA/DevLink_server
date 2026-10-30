package com.devlink.server.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerMessageSenderTest {
    @Test
    void concurrentPongAndTaskMessagesNeverWriteToSameSocketAtOnce() throws Exception {
        ConnectionRegistry connections = new ConnectionRegistry();
        BrokerMessageSender sender = new BrokerMessageSender(connections, new ObjectMapper());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        connections.register("11", session);
        AtomicInteger writers = new AtomicInteger();
        AtomicInteger maximumWriters = new AtomicInteger();
        AtomicInteger sent = new AtomicInteger();
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CountDownLatch competingSends = new CountDownLatch(3);
        doAnswer(invocation -> {
            int active = writers.incrementAndGet();
            maximumWriters.accumulateAndGet(active, Math::max);
            try {
                if (sent.getAndIncrement() == 0) {
                    firstWrite.countDown();
                    assertThat(releaseFirstWrite.await(5, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                writers.decrementAndGet();
            }
            return null;
        }).when(session).sendMessage(any());

        // 첫 쓰기를 멈춘 상태에서 다른 메시지 전송도 시작해 실제 동시 호출을 만든다.
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> results = new ArrayList<>();
            results.add(executor.submit(() -> sender.sendIfCurrent("11", session, Map.of("type", "TASK_CREATED"))));
            try {
                assertThat(firstWrite.await(5, TimeUnit.SECONDS)).isTrue();
                for (int index = 0; index < 3; index++) {
                    results.add(executor.submit(() -> {
                        competingSends.countDown();
                        return sender.sendIfCurrent("11", session, Map.of("type", "HEARTBEAT"));
                    }));
                }
                assertThat(competingSends.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                releaseFirstWrite.countDown();
            }
            for (var result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(sent).hasValue(4);
        assertThat(maximumWriters).hasValue(1);
    }
}
