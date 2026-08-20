package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxRelay;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 아웃박스 릴레이의 <b>독성 행(poison row)</b> 실패 모드 재현.
 *
 * <p>{@link OutboxRelay#publishPending()}은 한 건이라도 실패하면 남은 배치를 중단한다. 그 판단은
 * <b>일시적 실패</b>(브로커 다운)에는 옳다 — 순서를 지키고 다음 틱에 재시도하면 복구된다.
 * 그러나 <b>결정적 실패</b>(payload 역직렬화 불가)에는 성립하지 않는다. 다음 틱에도 같은 행이
 * {@code findByStatusOrderByCreatedAtAsc}의 맨 앞에 다시 오므로 영원히 같은 자리에서 멈춘다.
 *
 * <p>결정적 실패를 고른 이유: 브로커는 살아 있고 Kafka 발행 자체는 성공할 수 있는 상태에서
 * <b>행 하나의 내용</b>만으로 전체가 멈추는지를 분리해 보기 위함이다. 브로커를 죽이면
 * "당연히 안 나간다"가 되어 head-of-line 차단을 증명하지 못한다.
 *
 * <p>사용자 요청은 전부 성공하고 이벤트만 조용히 멈추므로, 이 결함은 에러율에 나타나지 않는다.
 */
@SpringBootTest
@Testcontainers
class OutboxPoisonEventIntegrationTest {

    /** 뒤따르는 정상 이벤트 수. */
    private static final int HEALTHY = 3;
    /** 릴레이 틱 반복 횟수 — "다음 틱에 재시도하면 복구된다"가 성립하는지 보려면 여러 번 돌려야 한다. */
    private static final int TICKS = 5;
    private static final long ORDER_BASE = 7_000_000L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // 배경 스케줄러 비활성 — 릴레이 틱을 테스트가 직접 돌려 결정적으로 만든다.
        r.add("flowticket.scheduling.enabled", () -> "false");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
    }

    @Autowired OutboxEventRepository outboxRepository;
    @Autowired OutboxRelay relay;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("역직렬화 불가 행 하나가 다른 주문의 이벤트를 막지 않는다")
    void 독성_행이_뒤_이벤트를_막지_않는다() {
        appendPoison(ORDER_BASE - 1);
        sleepPastTimestampResolution();
        for (int i = 0; i < HEALTHY; i++) {
            appendHealthy(ORDER_BASE + i);
        }

        runRelayTicks();

        assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED))
                .as("브로커는 정상이다. 앞의 한 행이 깨졌다는 이유로 뒤의 %d건이 멈추면 안 된다", HEALTHY)
                .isEqualTo(HEALTHY);
    }

    @Test
    @DisplayName("재시도로 성공할 수 없는 행은 릴레이 후보에서 빠진다")
    void 독성_행은_무한_재시도되지_않는다() {
        UUID poisonId = appendPoison(ORDER_BASE - 1);

        runRelayTicks();

        // 본질 계약은 "더 이상 릴레이 후보가 아니다"이다 — 몇 번 시도했는지가 아니라.
        List<OutboxEvent> candidates =
                outboxRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, 100));
        assertThat(candidates)
                .as("몇 번을 시도해도 결과가 같은 행은 배치 선두를 계속 차지하면 안 된다")
                .extracting(OutboxEvent::getId)
                .doesNotContain(poisonId);

        OutboxEvent poison = outboxRepository.findById(poisonId).orElseThrow();
        assertThat(poison.getStatus())
                .as("격리 상태로 남아야 운영자가 원인을 보고 판단할 수 있다(삭제하지 않는다)")
                .isEqualTo(OutboxStatus.DEAD);
        // 부가 검증 — 시도 횟수 자체는 구현에 따라 달라질 수 있다(결정적 실패는 1회로 판정 가능).
        assertThat(poison.getAttempts())
                .as("시도 횟수에 상한이 없으면 운영자가 개입할 때까지 무한히 증가한다")
                .isLessThan(TICKS);
        assertThat(poison.getLastError())
                .as("격리 근거가 없으면 운영자가 폐기/복구를 판단할 수 없다")
                .isNotBlank();
    }

    /**
     * ⚠️ <b>이 테스트는 현재 결함의 재현이 아니다.</b> 지금 프로덕션에서 아웃박스에 이벤트를 넣는
     * 경로는 {@code PaymentService.appendOutbox("order.paid", ...)} 하나뿐이고(환불은 SSE로만 나간다),
     * 한 주문에서 아웃박스 이벤트가 연속으로 만들어지는 시나리오는 존재하지 않는다.
     *
     * <p>그럼에도 고정하는 이유: 릴레이는 aggregateId를 Kafka 파티션 키로 써 <b>같은 aggregate의
     * 순서를 보존하도록 설계돼 있다.</b> 이번 수정이 head-of-line 차단을 없애면서 그 성질을 실수로
     * 깨뜨리기 쉬운데(전부 건너뛰면 되니까), 이벤트 종류가 늘어난 뒤에는 깨진 것을 알아채기 어렵다.
     */
    @Test
    @DisplayName("격리된 이벤트와 같은 주문의 후속 이벤트는 추월하지 않는다")
    void 같은_aggregate_후속_이벤트는_보류된다() {
        long blockedOrder = ORDER_BASE + 100;
        long otherOrder = ORDER_BASE + 200;

        appendPoison(blockedOrder);
        sleepPastTimestampResolution();
        UUID followerId = appendHealthy(blockedOrder); // 같은 주문의 후속 — 앞이 못 나갔으니 보류
        UUID otherId = appendHealthy(otherOrder);      // 다른 주문 — 영향받을 이유가 없다

        runRelayTicks();

        assertThat(outboxRepository.findById(otherId).orElseThrow().getStatus())
                .as("다른 aggregate까지 막으면 전역 정지 문제를 그대로 남기는 것이다")
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outboxRepository.findById(followerId).orElseThrow().getStatus())
                .as("앞 이벤트가 영영 안 나간 채 뒤 이벤트만 나가면 소비자가 인과를 거꾸로 본다")
                .isEqualTo(OutboxStatus.PENDING);
    }

    private void runRelayTicks() {
        for (int tick = 0; tick < TICKS; tick++) {
            relay.publishPending();
        }
    }

    /** {@code OrderEvent}로 역직렬화할 수 없는 payload. 예: 스키마 변경·손상·구버전 형식. */
    private UUID appendPoison(long orderId) {
        UUID id = UUID.randomUUID();
        outboxRepository.save(
                new OutboxEvent(id, "order", orderId, "order.paid", "{\"broken\": "));
        return id;
    }

    private UUID appendHealthy(long orderId) {
        UUID eventId = UUID.randomUUID();
        try {
            String payload = mapper.writeValueAsString(new OrderEvent("order.paid", orderId, eventId));
            outboxRepository.save(new OutboxEvent(eventId, "order", orderId, "order.paid", payload));
            return eventId;
        } catch (Exception e) {
            throw new IllegalStateException("아웃박스 적재 실패", e);
        }
    }

    private void sleepPastTimestampResolution() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
