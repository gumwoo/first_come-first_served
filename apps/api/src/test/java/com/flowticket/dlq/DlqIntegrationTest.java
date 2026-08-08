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
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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
        // 배경 스케줄러 비활성 — @Scheduled는 initialDelay가 없어 컨텍스트 기동 즉시 한 번 발사되고,
        // 그 UPDATE가 테스트 초기화 TRUNCATE와 데드락을 만든다. 주기를 늘리는 것으로는 못 막는다.
        r.add("flowticket.scheduling.enabled", () -> "false");
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
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
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, "1", OrderEvent.of("order.paid", 111L));

        DlqMessage row = awaitDlqRowFor(111L);
        assertThat(row.getStatus()).isEqualTo(DlqStatus.PENDING);
        assertThat(row.getTopic()).isEqualTo(KafkaConfig.ORDER_EVENTS_TOPIC);
        assertThat(row.getErrorMessage()).contains("boom");
    }

    @Test
    void 역직렬화가_실패하는_독성_메시지도_DLQ로_간다() throws Exception {
        // 이 테스트가 없어서 배포에서 뚫렸다(TS-020). JsonDeserializer를 직접 쓰면 역직렬화가
        // **poll() 단계**에서 터져 리스너에 도달하지 못하고, DefaultErrorHandler(+DLT)가 개입할
        // 수 없다. 결과는 같은 메시지 무한 재시도 — 파드는 Running이고 readiness도 UP인데
        // 처리가 멈춘 채 CPU만 태운다(실측: 파드 711m, 노드 100%, HPA가 부하로 오해해 스케일업).
        //
        // 위 두 테스트는 **역직렬화에 성공한 뒤** 리스너에서 던지는 경우라 이 경로를 못 잡는다.
        // 그래서 타입 헤더 없는 평문을 직접 넣는다 — 재시도해도 절대 성공하지 않는 유형이다.
        try (KafkaProducer<String, String> raw = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            raw.send(new ProducerRecord<>(KafkaConfig.ORDER_EVENTS_TOPIC, "poison", "not-json-at-all")).get();
        }

        // 무한 재시도에 빠지면 DLQ 행이 영영 생기지 않아 여기서 타임아웃된다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(dlqRepository.findAll())
                        .as("독성 메시지는 재시도로 해결되지 않으므로 DLT로 넘어가야 한다")
                        .anySatisfy(m -> assertThat(m.getTopic()).isEqualTo(KafkaConfig.ORDER_EVENTS_TOPIC)));
    }

    @Test
    void DLQ_폐기는_상태를_DISCARDED로() {
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, "2", OrderEvent.of("order.paid", 222L));
        Long id = awaitDlqRowFor(222L).getId();

        adminDlqService.discard(id);

        assertThat(dlqRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DlqStatus.DISCARDED);
    }

    @Test
    void DLQ_재시도는_원본토픽_재발행_후_RETRIED로() {
        kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, "3", OrderEvent.of("order.paid", 333L));
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
