package com.flowticket.order.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxRelay;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S08 아웃박스(ADR-010): 아웃박스에 적재된 이벤트가 <b>릴레이 → Kafka → consumer → SSE</b>로 전달되고
 * PUBLISHED로 마킹되는 전체 경로를 검증. 릴레이는 스케줄러 대신 직접 호출해 결정적으로 만든다.
 */
@SpringBootTest
@Testcontainers
class OrderEventKafkaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("queue.admit-interval-ms", () -> "3600000");
        r.add("seat.sweep-interval-ms", () -> "3600000");
        r.add("order.sweep-interval-ms", () -> "3600000");
        r.add("outbox.relay-interval-ms", () -> "3600000"); // 자동 릴레이 비활성 → 테스트가 직접 호출
    }

    @SpyBean OrderSseRegistry orderSse;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired OrderEventConsumer consumer;
    @Autowired ObjectMapper mapper;

    @Test
    void 아웃박스에_적재된_이벤트가_릴레이거쳐_SSE로_전달되고_PUBLISHED로_마킹된다() throws Exception {
        long orderId = 987654L;
        UUID eventId = appendOutbox(orderId);

        relay.publishPending();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(orderSse).broadcast(eq(orderId), eq("order.paid"), any()));
        // publish-then-mark: 발행 성공을 확인한 뒤에만 PUBLISHED로 전이
        assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void 같은_eventId가_두번_전달돼도_한번만_브로드캐스트된다() {
        // 릴레이 재시도·리밸런스로 생기는 at-least-once 중복을 소비자 멱등(SETNX)이 흡수하는지.
        long orderId = 424242L;
        OrderEvent event = new OrderEvent("order.paid", orderId, UUID.randomUUID());

        consumer.onOrderEvent(event);
        consumer.onOrderEvent(event); // 중복 전달

        verify(orderSse, times(1)).broadcast(eq(orderId), eq("order.paid"), any());
    }

    private UUID appendOutbox(long orderId) throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
        outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));
        return eventId;
    }
}
