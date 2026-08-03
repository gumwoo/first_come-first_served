package com.flowticket.global.sse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.seat.sse.SeatSseRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * ① SSE 팬아웃(멀티 Pod). 한 인스턴스(podA)에서 broadcast하면 Redis pub/sub을 통해 다른
 * 인스턴스(podB)가 수신해 자기 로컬 SSE로 전달하는지 검증 — 두 SeatSseRegistry 인스턴스를
 * 같은 Redis에 물려 "연결 Pod ≠ 소비 Pod" 상황을 재현한다.
 */
@SpringBootTest
class SseFanoutIntegrationTest extends IntegrationTestSupport {

    @Autowired SsePubSub pubSub;
    @Autowired RedisConnectionFactory connectionFactory;

    @Test
    void 한_Pod의_broadcast가_다른_Pod의_로컬전달로_팬아웃된다() throws Exception {
        SeatSseRegistry podA = new SeatSseRegistry(1_800_000L, pubSub);
        SeatSseRegistry podB = spy(new SeatSseRegistry(1_800_000L, pubSub));

        // podB만 Redis 채널 구독(= podB에 SSE 연결이 있는 Pod)
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.start();
        try {
            container.addMessageListener(podB, new ChannelTopic(SeatSseRegistry.CHANNEL));

            // podA(연결 없음)에서 발행 → Redis pub/sub → podB가 수신해 deliverLocal 실행되어야 함
            podA.broadcast(99L, "seat.hold.expired", Map.of("seatIds", List.of(1, 2)));

            verify(podB, timeout(3000)).deliverLocal(eq(99L), eq("seat.hold.expired"), any());
        } finally {
            container.stop();
            container.destroy();
        }
    }
}
