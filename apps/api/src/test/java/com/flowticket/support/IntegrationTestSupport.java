package com.flowticket.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * DB·Redis를 쓰는 통합테스트의 공통 베이스(Kafka 없음).
 *
 * 컨테이너만 공유해서는 절반만 빨라진다. Spring 컨텍스트 캐시 키에는
 * @DynamicPropertySource 메서드 집합이 들어가므로, 클래스마다 자기 메서드를 선언하면
 * 값이 같아도 매번 새 컨텍스트가 뜬다. 그래서 이 메서드를 상속해 같은 커스터마이저를 공유한다.
 *
 * 튜닝 값은 @TestPropertySource로 둔다. 하위 클래스가 같은 키를 다시 선언하면
 * 그 값이 우선하므로(상속 병합), 예외적인 테스트만 필요한 항목을 덮어쓸 수 있다.
 * (반대로 여기서 동적 프로퍼티로 넣으면 하위의 @TestPropertySource가 이기지 못한다.)
 *
 * 기본값은 애플리케이션 기본값과 같게 맞춰(capacity 100·hold-ttl 300·max-per-user 4) 동작 변화가 없고,
 * 스케줄러 주기만 크게 잡아 배경 워커가 테스트에 끼어들지 않게 한다.
 */
@TestPropertySource(properties = {
        // 컨텍스트가 여러 개 캐시된 채 살아 있으므로(각자 커넥션 풀 보유) 풀을 작게 잡는다.
        // 기본값(10)이면 컨텍스트 몇 개만 떠도 Postgres 최대 커넥션을 넘겨 기동이 실패한다.
        "spring.datasource.hikari.maximum-pool-size=4",
        "jwt.secret=integration-test-secret-0123456789-0123456789-0123456789",
        "queue.capacity=100",
        "seat.max-per-user=4",
        "seat.hold-ttl=300",
        // 배경 워커 비활성: 필요한 테스트는 서비스 메서드를 직접 호출해 결정적으로 검증한다.
        //
        // 주기를 늘리는 것으로는 비활성이 안 된다. @Scheduled(fixedRateString = ...)에는 initialDelay가
        // 없어 컨텍스트가 뜨는 순간 첫 실행이 즉시 발사되고, 그 UPDATE(RowExclusive)가 다른 테스트의
        // TRUNCATE(ACCESS EXCLUSIVE)와 데드락을 만든다.
        "flowticket.scheduling.enabled=false",
        // 토픽 자동 생성을 끈다. 이 그룹은 브로커가 없어 KafkaAdmin이 약 40초 재시도한 뒤 포기한다(IMP-019).
        // 리스너 auto-startup으로는 안 된다. 멈추는 주체가 admin 클라이언트다.
        // Kafka를 실제로 쓰는 테스트는 이 베이스를 상속하지 않는다.
        "spring.kafka.admin.auto-create=false",
})
public abstract class IntegrationTestSupport {

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        TestContainers.datasource(registry);
        // 브로커를 쓰지 않는 테스트: 연결 불가 주소를 명시해 로컬에 떠 있는 Kafka에 얹히지 않게 한다.
        // (missing-topics-fatal=false + max.block.ms 바운드라 기동은 정상)
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisForReset;

    /**
     * 컨테이너를 공유하는 대신 상태는 매 테스트마다 초기화한다(상위 @BeforeEach가 먼저 실행되므로
     * 하위 클래스의 시드보다 앞선다). 이렇게 해야 클래스 간 데이터가 새지 않는다.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetSharedState() {
        TestContainers.reset(dataSource, redisForReset);
    }
}
