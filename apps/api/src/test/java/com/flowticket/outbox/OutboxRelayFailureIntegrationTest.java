package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxPublisher;
import com.flowticket.outbox.service.OutboxRelay;
import com.flowticket.support.IntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * 발행 실패 → 마킹 순서(publish-then-mark, ADR-010·028).
 *
 * 빈 릴레이(프록시를 거쳐 @Transactional이 살아 있음)를 쓰고 발행기만 목으로 바꾼다. 프록시 없이 직접 만든 릴레이는
 * 트랜잭션도 빠져 attempts·lastError가 DB에 남지 않으므로(kafka-removal-design §2.3) 판정은 DB에서 다시 읽는다.
 * Redis 컨테이너를 멈추는 방식은 쓰지 않는다 — ShedLock 락 획득이 같은 Redis에 기대 발행 전에 틱이 건너뛰어진다.
 */
@SpringBootTest
class OutboxRelayFailureIntegrationTest extends IntegrationTestSupport {

    @MockBean OutboxPublisher publisher;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired ObjectMapper mapper;

    @Test
    void 발행이_실패하면_PENDING을_유지하고_시도와_오류를_남기며_배치_나머지는_보내지_않는다() throws Exception {
        append(1L);
        append(2L);
        when(publisher.publish(any())).thenThrow(new RedisConnectionFailureException("redis down"));

        relay.publishPending();

        verify(publisher, times(1)).publish(any()); // 첫 실패에서 배치를 멈춘다
        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(OutboxStatus.PENDING));
        assertThat(rows).extracting(OutboxEvent::getAttempts).containsExactlyInAnyOrder(1, 0);
        assertThat(rows).filteredOn(r -> r.getAttempts() == 1)
                .singleElement()
                .satisfies(r -> assertThat(r.getLastError()).contains("redis down"));
    }

    @Test
    void 복구되면_다음_틱에_발행하고_오류를_지운다() throws Exception {
        UUID id = append(3L);
        when(publisher.publish(any()))
                .thenThrow(new RedisConnectionFailureException("redis down"))
                .thenReturn(1L);

        relay.publishPending(); // 실패
        relay.publishPending(); // 복구

        OutboxEvent row = outboxRepository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getLastError()).isNull();
        verify(publisher, times(2)).publish(any());
    }

    @Test
    void 구독자가_0명이어도_발행_호출이_성공하면_PUBLISHED다() throws Exception {
        // PUBLISHED = 발행 호출 성공. 수신 보장이 아니다(ADR-028 §6). 0은 실패로 보지 않는다.
        UUID id = append(4L);
        when(publisher.publish(any())).thenReturn(0L);

        relay.publishPending();

        assertThat(outboxRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    private UUID append(long orderId) throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
        outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));
        return eventId;
    }
}
