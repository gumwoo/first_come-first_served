package com.flowticket.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.flowticket.dlq.domain.DlqMessage;
import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.dlq.service.AdminDlqService;
import com.flowticket.global.config.KafkaConfig;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.sse.OrderSseRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * S07 Phase 4c: 컨슈머 처리 실패 → 재시도 소진 → DLT → dlq_messages 적재, 그리고 재시도/폐기.
 * OrderSseRegistry를 던지도록 mock해 order-events 소비를 강제 실패시킨다.
 */
@SpringBootTest
@Testcontainers
class DlqIntegrationTest {

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
    }

    @MockBean OrderSseRegistry orderSse; // 소비 실패를 강제하기 위한 poison
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired DlqMessageRepository dlqRepository;
    @Autowired AdminDlqService adminDlqService;

    @BeforeEach
    void setup() {
        dlqRepository.deleteAll();
        doThrow(new RuntimeException("boom")).when(orderSse).broadcast(anyLong(), anyString(), any());
    }

    @Test
    void 소비실패가_재시도소진후_DLQ에_적재된다() {
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, "1", new OrderEvent("order.paid", 111L));

        DlqMessage row = awaitDlqRowFor(111L);
        assertThat(row.getStatus()).isEqualTo(DlqStatus.PENDING);
        assertThat(row.getTopic()).isEqualTo(KafkaConfig.ORDER_EVENTS_TOPIC);
        assertThat(row.getErrorMessage()).contains("boom");
    }

    @Test
    void DLQ_폐기는_상태를_DISCARDED로() {
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, "2", new OrderEvent("order.paid", 222L));
        Long id = awaitDlqRowFor(222L).getId();

        adminDlqService.discard(id);

        assertThat(dlqRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DlqStatus.DISCARDED);
    }

    @Test
    void DLQ_재시도는_원본토픽_재발행_후_RETRIED로() {
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, "3", new OrderEvent("order.paid", 333L));
        Long id = awaitDlqRowFor(333L).getId();

        adminDlqService.retry(id);

        assertThat(dlqRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DlqStatus.RETRIED);
    }

    /**
     * 이 테스트가 보낸 orderId를 가진 DLQ 행만 골라 기다린다.
     * 재시도 재발행분 등 다른 테스트가 남긴 행이 비동기로 섞여도 흔들리지 않게(격리).
     */
    private DlqMessage awaitDlqRowFor(long orderId) {
        String needle = "\"orderId\":" + orderId;
        await().atMost(Duration.ofSeconds(30))
                .until(() -> dlqRepository.findAll().stream().anyMatch(m -> m.getPayload().contains(needle)));
        return dlqRepository.findAll().stream()
                .filter(m -> m.getPayload().contains(needle))
                .findFirst().orElseThrow();
    }
}
