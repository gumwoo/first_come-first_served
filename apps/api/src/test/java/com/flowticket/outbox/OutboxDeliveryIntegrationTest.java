package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.global.config.KafkaConfig;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxRelay;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * IMP-011 측정(ADR-010): <b>브로커 장애 중 발행된 이벤트가 유실되는가</b>를 before/after로 카운트한다.
 *
 * <p>장애는 KafkaTemplate.send가 실패하도록 스텁해 재현한다(결정적). 두 경로를 같은 장애 창에 넣고,
 * 복구 후 실제로 소비자에 도달한 건수를 세어 유실을 측정한다.
 * <ul>
 *   <li><b>before(naive)</b> — 구 AFTER_COMMIT 브리지: 발행 실패를 삼킨다. 복구해도 재발행할 근거가
 *       어디에도 없어 영구 유실.</li>
 *   <li><b>after(outbox)</b> — 이벤트가 결제와 같은 커밋으로 DB에 남아 PENDING 유지 → 복구 후 릴레이가
 *       재시도해 전량 발행.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
class OutboxDeliveryIntegrationTest {

    private static final int TRIALS = 10;
    private static final long NAIVE_BASE = 5_000_000L;  // before 경로 orderId 구간
    private static final long OUTBOX_BASE = 6_000_000L; // after 경로 orderId 구간

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
        r.add("outbox.relay-interval-ms", () -> "3600000"); // 릴레이는 테스트가 직접 호출(결정적)
    }

    @SpyBean OrderSseRegistry orderSse;
    @SpyBean KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired ObjectMapper mapper;

    /** 소비자까지 실제로 도달한 orderId(= 유실되지 않은 이벤트). */
    private final Set<Long> delivered = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        delivered.clear();
        doAnswer(inv -> {
            delivered.add(inv.getArgument(0, Long.class));
            return null;
        }).when(orderSse).broadcast(any(), eq("order.paid"), any());
    }

    @Test
    void IMP011_브로커_장애중_이벤트_유실_before_after() {
        // ── 브로커 장애 시작(발행 실패) ─────────────────────────────
        doThrow(new KafkaException("broker down"))
                .when(kafkaTemplate).send(anyString(), anyString(), any());

        // before(naive): 실패를 삼키고 아무 기록도 남기지 않는다(구 브리지 동작 그대로)
        for (int i = 0; i < TRIALS; i++) {
            naivePublishSwallowing(OrderEvent.of("order.paid", NAIVE_BASE + i));
        }

        // after(outbox): 결제와 같은 커밋으로 남은 행을 릴레이가 발행 시도 → 실패해도 PENDING 유지
        for (int i = 0; i < TRIALS; i++) {
            appendOutbox(OUTBOX_BASE + i);
        }
        relay.publishPending();
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING))
                .as("장애 중에도 이벤트는 DB에 보존된다(유실 대신 재시도 대상)")
                .isEqualTo(TRIALS);

        // ── 브로커 복구 ────────────────────────────────────────────
        reset(kafkaTemplate);

        // naive는 재발행할 근거가 없어 아무 일도 일어나지 않는다. outbox만 재시도된다.
        relay.publishPending();

        await().atMost(Duration.ofSeconds(30))
                .until(() -> deliveredIn(OUTBOX_BASE) == TRIALS);

        int lostBefore = TRIALS - deliveredIn(NAIVE_BASE);
        int lostAfter = TRIALS - deliveredIn(OUTBOX_BASE);

        // 벤치마크(benchmarks/outbox-delivery-*.json)에 박제되는 수치를 테스트가 강제한다.
        assertThat(lostBefore).as("before: 장애 중 발행분 전량 영구 유실").isEqualTo(TRIALS);
        assertThat(lostAfter).as("after: 복구 후 전량 재발행 → 유실 0").isZero();
        assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(TRIALS);
    }

    /** 구 OrderEventKafkaBridge 동작: 발행 실패를 로그만 남기고 삼킨다(복구 근거 없음 = 영구 유실). */
    private void naivePublishSwallowing(OrderEvent event) {
        try {
            kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, String.valueOf(event.orderId()), event);
        } catch (Exception ignored) {
            // 결제는 이미 커밋됐고 DB가 진실원 — 알림 유실은 감수(구 설계)
        }
    }

    private void appendOutbox(long orderId) {
        UUID eventId = UUID.randomUUID();
        try {
            String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
            outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));
        } catch (Exception e) {
            throw new IllegalStateException("아웃박스 적재 실패", e);
        }
    }

    private int deliveredIn(long base) {
        return (int) delivered.stream()
                .filter(id -> id >= base && id < base + TRIALS)
                .count();
    }
}
