package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.AdminOutboxService;
import com.flowticket.outbox.service.OutboxRelay;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 격리된(DEAD) 아웃박스 행의 <b>운영 창구</b>. 조회만이 아니라, 운영자 판단이 릴레이 동작에
 * 실제로 반영되는지를 본다.
 *
 * <p>요점은 <b>폐기가 aggregate 차단을 푸는가</b>이다. 릴레이는 DEAD만 차단 사유로 보므로,
 * 폐기는 "이 이벤트는 영영 안 나간다는 것을 받아들인다"는 선언이면서 동시에 후속 이벤트의
 * 해방이다. 이 연결이 끊어져 있으면 창구가 있어도 막힌 aggregate를 풀 수 없다.
 */
@SpringBootTest
@Testcontainers
class AdminOutboxIntegrationTest {

    private static final int TICKS = 3;
    private static final long ORDER_BASE = 8_000_000L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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
    @Autowired AdminOutboxService adminOutboxService;
    @Autowired OutboxRelay relay;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("격리된 행을 상태로 걸러 조회하고, 무엇이 깨졌는지 볼 수 있다")
    void 격리된_행을_조회한다() {
        appendPoison(ORDER_BASE);
        sleepPastTimestampResolution();
        appendHealthy(ORDER_BASE + 1);
        runRelayTicks();

        var dead = adminOutboxService.list(OutboxStatus.DEAD.name(), 0, 20);

        assertThat(dead.items()).hasSize(1);
        var row = dead.items().get(0);
        assertThat(row.aggregateId()).isEqualTo(ORDER_BASE);
        assertThat(row.payload())
                .as("무엇이 깨졌는지 못 보면 폐기/복구를 판단할 수 없다")
                .isNotBlank();
        assertThat(row.lastError()).as("격리 근거").isNotBlank();
        assertThat(dead.total()).as("정상 발행분은 DEAD 목록에 섞이지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("폐기하면 같은 주문의 후속 이벤트가 다시 흐른다")
    void 폐기하면_aggregate_차단이_풀린다() {
        UUID poisonId = appendPoison(ORDER_BASE);
        sleepPastTimestampResolution();
        UUID followerId = appendHealthy(ORDER_BASE); // 같은 주문 — 선행 DEAD에 막힌다
        runRelayTicks();

        assertThat(outboxRepository.findById(followerId).orElseThrow().getStatus())
                .as("폐기 전에는 막혀 있어야 이 테스트가 성립한다")
                .isEqualTo(OutboxStatus.PENDING);

        adminOutboxService.discard(poisonId);
        runRelayTicks();

        assertThat(outboxRepository.findById(poisonId).orElseThrow().getStatus())
                .as("폐기한 행은 지우지 않는다 — 무엇을 포기했는지가 남아야 한다")
                .isEqualTo(OutboxStatus.DISCARDED);
        assertThat(outboxRepository.findById(followerId).orElseThrow().getStatus())
                .as("차단 사유가 사라지면 후속 이벤트는 나가야 한다")
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("재발행으로 되돌리면 다시 릴레이 대상이 된다")
    void 재발행으로_되돌린다() {
        UUID poisonId = appendPoison(ORDER_BASE);
        runRelayTicks();

        adminOutboxService.requeue(poisonId);

        OutboxEvent requeued = outboxRepository.findById(poisonId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(requeued.getLastError())
                .as("되돌린 행에 지난 격리 근거가 남아 있으면 미해결로 오해한다")
                .isNull();

        // payload가 그대로이므로 릴레이는 다시 격리한다 — 되돌리기는 '고쳐졌을 때' 쓰는 수단이다.
        runRelayTicks();
        assertThat(outboxRepository.findById(poisonId).orElseThrow().getStatus())
                .as("내용이 그대로면 결과도 그대로다 — 운영자 조작이 결정적 실패를 없애지는 않는다")
                .isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    @DisplayName("PENDING 행은 운영자 개입 대상이 아니다")
    void 미발행_행은_폐기할_수_없다() {
        UUID pendingId = appendHealthy(ORDER_BASE); // 릴레이를 돌리지 않아 PENDING 그대로

        assertThatThrownBy(() -> adminOutboxService.discard(pendingId))
                .as("브로커 장애로 밀려 있을 뿐 언젠가 나갈 이벤트다 — 개입하면 유실이 된다")
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATE_TRANSITION);
    }

    private void runRelayTicks() {
        for (int tick = 0; tick < TICKS; tick++) {
            relay.publishPending();
        }
    }

    private UUID appendPoison(long orderId) {
        UUID id = UUID.randomUUID();
        outboxRepository.save(new OutboxEvent(id, "order", orderId, "order.paid", "{\"broken\": "));
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
