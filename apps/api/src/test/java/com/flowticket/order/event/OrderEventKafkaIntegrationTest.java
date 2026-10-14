package com.flowticket.order.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * Kafka 소비자(OrderEventConsumer)의 멱등만 남은 검증.
 *
 * 릴레이는 ADR-028부터 Kafka가 아니라 Redis 채널로 발행한다(그 경로는 outbox 패키지의 테스트가 본다).
 * 소비자는 Kafka 코드 제거(P2b-2)까지 남아 있으므로 그때까지 멱등만 확인하고, 제거와 함께 이 테스트도 지운다.
 */
@SpringBootTest
class OrderEventKafkaIntegrationTest extends IntegrationTestSupport {

    @SpyBean OrderSseRegistry orderSse;
    @Autowired OrderEventConsumer consumer;

    @Test
    void 같은_eventId가_두번_전달돼도_한번만_브로드캐스트된다() {
        // 릴레이 재시도·리밸런스로 생기는 at-least-once 중복을 소비자 멱등(SETNX)이 흡수하는지.
        long orderId = 424242L;
        OrderEvent event = new OrderEvent("order.paid", orderId, UUID.randomUUID());

        consumer.onOrderEvent(event);
        consumer.onOrderEvent(event); // 중복 전달

        verify(orderSse, times(1)).broadcast(eq(orderId), eq("order.paid"), any());
    }
}
