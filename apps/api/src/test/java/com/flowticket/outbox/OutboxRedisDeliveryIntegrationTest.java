package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxRelay;
import com.flowticket.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * 아웃박스 → Redis 채널(sse:order) → 각 파드의 구독자 경로(ADR-028).
 *
 * 예전 Kafka 소비자가 마지막 홉에서 보내던 봉투(key=orderId, event=order.paid, data={"orderId": id})와 같아야
 * 프론트가 받는 SSE 프레임이 바뀌지 않는다. 릴레이는 스케줄러 대신 직접 호출해 결정적으로 만든다.
 */
@SpringBootTest
class OutboxRedisDeliveryIntegrationTest extends IntegrationTestSupport {

    @SpyBean OrderSseRegistry orderSse;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired ObjectMapper mapper;

    @Test
    void 아웃박스_행이_sse_order_채널로_나가_구독자에게_같은_봉투로_도달하고_PUBLISHED가_된다() throws Exception {
        long orderId = 987_654L;
        UUID eventId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
        outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));

        relay.publishPending();

        // 구독자(OrderSseRegistry.onMessage)가 봉투를 풀어 로컬 SSE로 넘긴 호출을 본다.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                verify(orderSse).deliverLocal(eq(orderId), eq("order.paid"), argThat(data ->
                        data instanceof Map<?, ?> map
                                && map.get("orderId") instanceof Number n
                                && n.longValue() == orderId)));

        OutboxEvent row = outboxRepository.findById(eventId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();
    }
}
