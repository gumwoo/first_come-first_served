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
        // 컨텍스트가 여러 개 캐시된 채 살아 있으므로(각자 커넥션 풀 보유) 풀을 작게 잡는다 —
        // 기본값(10)이면 컨텍스트 몇 개만 떠도 Postgres 최대 커넥션을 넘겨 기동이 실패한다.
        "spring.datasource.hikari.maximum-pool-size=4",
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

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisForReset;

    /**
     * 컨테이너를 공유하는 대신 <b>상태는 매 테스트마다 초기화</b>한다(상위 @BeforeEach가 먼저 실행되므로
     * 하위 클래스의 시드보다 앞선다). 이렇게 해야 클래스 간 데이터가 새지 않는다.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetSharedState() {
        TestContainers.reset(dataSource, redisForReset);
    }
}
