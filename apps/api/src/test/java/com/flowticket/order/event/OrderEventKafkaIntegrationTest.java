package com.flowticket.order.event;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.flowticket.order.sse.OrderSseRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S07 Phase 4b: 트랜잭션 커밋 후 발행된 OrderEvent가 Kafka를 거쳐 consumer에서 SSE로 브로드캐스트되는
 * 전체 경로를 검증. AFTER_COMMIT이므로 커밋된 뒤에만 발행된다.
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
    }

    @SpyBean OrderSseRegistry orderSse;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void 커밋후_발행된_주문이벤트가_Kafka거쳐_SSE로_브로드캐스트된다() {
        long orderId = 987654L;

        // 트랜잭션 안에서 발행 → 커밋되어야 AFTER_COMMIT 브리지가 Kafka로 발행.
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                events.publishEvent(new OrderEvent("order.paid", orderId)));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                verify(orderSse).broadcast(eq(orderId), eq("order.paid"), any()));
    }
}
