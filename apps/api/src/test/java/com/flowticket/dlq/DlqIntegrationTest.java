package com.flowticket.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.flowticket.dlq.domain.DlqMessage;
import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.dlq.service.AdminDlqService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.global.config.KafkaConfig;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.sse.OrderSseRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
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
 * 컨슈머 처리 실패 → 재시도 소진 → DLT → dlq_messages 적재, 그리고 재시도/폐기.
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
    @Autowired ObjectMapper objectMapper;

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
        // TS-020 회귀 — 역직렬화 단계의 실패도 DLQ로 가는가. 타입 헤더 없는 평문을 직접 넣는다.
        // 토픽만으로 단언하면 다른 테스트가 비동기로 남긴 DLQ 행을 보고 거짓 통과하므로, 이 메시지 전용 표식을 쓴다.
        String marker = "poison-" + UUID.randomUUID();

        try (KafkaProducer<String, String> raw = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            raw.send(new ProducerRecord<>(KafkaConfig.ORDER_EVENTS_TOPIC, "poison", marker)).get();
        }

        // 무한 재시도에 빠지면 이 표식을 가진 행이 영영 생기지 않아 타임아웃된다.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(dlqRepository.findAll())
                        .as("독성 메시지는 재시도로 해결되지 않으므로 DLT로 넘어가야 한다")
                        .anySatisfy(m -> {
                            // 1) 원본 바이트가 그대로 실려야 한다 — JsonSerializer로 나가면
                            //    base64 JSON이 되어 이 표식이 평문으로 남지 않는다.
                            assertThat(m.getPayload())
                                    .as("DLT 값은 원본 byte[]여야 한다(직렬화기 구성 확인)")
                                    .contains(marker);
                            // 2) 실패 사유가 역직렬화여야 한다. 리스너 실패("boom")와 구분된다.
                            assertThat(m.getErrorMessage())
                                    .as("역직렬화 실패로 DLT에 온 것이어야 한다")
                                    .containsIgnoringCase("deserial");
                            assertThat(m.getTopic()).isEqualTo(KafkaConfig.ORDER_EVENTS_TOPIC);
                        }));
    }

    @Test
    void 재발행이_실패하면_RETRIED로_바꾸지_않는다() throws Exception {
        // TS-026 회귀. 브로커 확인 없이 markRetried()하면 "DB는 재처리했다는데 실제로는 유실"이
        // 가능하고, RETRIED가 되면 운영자 목록에서도 사라진다.
        //
        // 발행 실패를 결정적으로 만들기 위해 Kafka 토픽명 규칙을 위반한 이름을 쓴다
        // (허용 문자는 [a-zA-Z0-9._-]). 브로커 다운을 흉내 내는 것보다 재현이 확실하다.
        String payload = objectMapper.writeValueAsString(OrderEvent.of("order.paid", 444L));
        DlqMessage row = dlqRepository.save(new DlqMessage("invalid topic name!", payload, "err"));

        assertThatThrownBy(() -> adminDlqService.retry(row.getId()))
                .as("발행이 확인되지 않으면 예외여야 한다")
                .isInstanceOf(BusinessException.class);

        assertThat(dlqRepository.findById(row.getId()).orElseThrow().getStatus())
                .as("발행 실패 시 상태는 그대로여야 운영자가 다시 시도할 수 있다")
                .isEqualTo(DlqStatus.PENDING);
    }

    @Test
    void 깨진_페이로드는_발행을_시도하지_않고_400으로_구분된다() {
        // 페이로드 파손은 몇 번을 눌러도 실패하므로 폐기 대상이고, 발행 실패는 나중에 다시 누르면
        // 되는 것이라 응답 코드로 구분돼야 한다.
        DlqMessage row = dlqRepository.save(
                new DlqMessage(KafkaConfig.ORDER_EVENTS_TOPIC, "not-json-at-all", "err"));

        assertThatThrownBy(() -> adminDlqService.retry(row.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);

        assertThat(dlqRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(DlqStatus.PENDING);
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
