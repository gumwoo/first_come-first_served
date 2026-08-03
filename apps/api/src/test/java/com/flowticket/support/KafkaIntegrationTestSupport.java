package com.flowticket.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 실제 Kafka 브로커가 필요한 통합테스트의 베이스(이벤트 백본·DLQ·아웃박스 검증).
 *
 * <p>{@link IntegrationTestSupport}를 상속하지 않고 <b>별도 베이스</b>로 둔다 — 상속하면
 * 상·하위 {@code @DynamicPropertySource}가 같은 키(`bootstrap-servers`)를 두 번 등록하게 되고,
 * 어느 쪽이 이기는지가 메서드 수집 순서에 의존해 불안정해진다. 명시적으로 나누는 편이 안전하다.
 *
 * <p>Postgres·Redis 컨테이너는 {@link TestContainers}에서 같은 인스턴스를 공유하므로,
 * 베이스를 나눠도 컨테이너가 중복 기동되지는 않는다.
 */
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-0123456789-0123456789-0123456789",
        "queue.capacity=100",
        "seat.max-per-user=4",
        "seat.hold-ttl=300",
        "queue.admit-interval-ms=3600000",
        "seat.sweep-interval-ms=3600000",
        "order.sweep-interval-ms=3600000",
        "outbox.relay-interval-ms=3600000",
        "payment.reconcile-interval-ms=3600000",
})
public abstract class KafkaIntegrationTestSupport {

    @DynamicPropertySource
    static void kafkaIntegrationProperties(DynamicPropertyRegistry registry) {
        TestContainers.datasource(registry);
        registry.add("spring.kafka.bootstrap-servers", TestContainers::kafkaBootstrapServers);
    }
}
