package com.flowticket.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * DB·Redis를 쓰는 통합테스트의 공통 베이스(Kafka 없음).
 *
 * <p><b>컨테이너만 공유해서는 절반만 빨라진다.</b> Spring 컨텍스트 캐시 키에는
 * {@code @DynamicPropertySource} <b>메서드 집합</b>이 들어가므로, 클래스마다 자기 메서드를 선언하면
 * 값이 같아도 매번 새 컨텍스트가 뜬다. 그래서 이 메서드를 <b>상속</b>해 같은 커스터마이저를 공유한다.
 *
 * <p>튜닝 값은 {@code @TestPropertySource}로 둔다 — 하위 클래스가 같은 키를 다시 선언하면
 * 그 값이 우선하므로(상속 병합), 예외적인 테스트만 필요한 항목을 덮어쓸 수 있다.
 * (반대로 여기서 동적 프로퍼티로 넣으면 하위의 {@code @TestPropertySource}가 이기지 못한다.)
 *
 * <p>기본값은 애플리케이션 기본값과 같게 맞춰(capacity 100·hold-ttl 300·max-per-user 4) 동작 변화가 없고,
 * 스케줄러 주기만 크게 잡아 배경 워커가 테스트에 끼어들지 않게 한다.
 */
@TestPropertySource(properties = {
        // 컨텍스트가 여러 개 캐시된 채 살아 있으므로(각자 커넥션 풀 보유) 풀을 작게 잡는다 —
        // 기본값(10)이면 컨텍스트 몇 개만 떠도 Postgres 최대 커넥션을 넘겨 기동이 실패한다.
        "spring.datasource.hikari.maximum-pool-size=4",
        "jwt.secret=integration-test-secret-0123456789-0123456789-0123456789",
        "queue.capacity=100",
        "seat.max-per-user=4",
        "seat.hold-ttl=300",
        // 배경 워커 비활성 — 필요한 테스트는 서비스 메서드를 직접 호출해 결정적으로 검증한다.
        //
        // ⚠️ 예전에는 주기를 3600000ms로 늘려 "비활성"이라고 적어 뒀지만 **실제로는 비활성이 아니었다.**
        // @Scheduled(fixedRateString = ...)에는 initialDelay가 없어 **컨텍스트가 뜨는 순간 첫 실행이
        // 즉시 발사**된다 — 주기를 늘려 미뤄지는 것은 두 번째 실행뿐이다. 컨텍스트가 여러 개라
        // 새 컨텍스트가 뜰 때마다 스윕이 발사됐고, 그 UPDATE(RowExclusive)가 다른 테스트의
        // TRUNCATE(ACCESS EXCLUSIVE)와 데드락을 만들어 CI가 6번 깨졌다.
        "flowticket.scheduling.enabled=false",
        // ⚠️ **토픽 자동 생성을 끈다.** 이 한 줄이 컨텍스트 부트를 41초 → 1초대로 바꾼다.
        //
        // KafkaConfig에 NewTopic 빈이 둘 있어(order-events, order-events.DLT) Spring Boot의
        // KafkaAdmin이 **부팅 시 브로커에 붙어 토픽을 만들려 한다.** 이 그룹은 브로커를 쓰지 않아
        // 아래 @DynamicPropertySource가 bootstrap을 연결 불가 주소로 박으므로, admin 클라이언트가
        // 붙지 못한 채 재시도하다 **약 40초 뒤에야 포기**한다.
        //
        // 실측(2026-08-16 CI) — 부트 마일스톤 타임라인:
        //   + 0.0s  Starting
        //   + 0.6s  Initialized JPA EntityManagerFactory   ← JPA·Flyway·Hikari·Repository 전부 0.6초
        //   + 1.4s  Kafka version 3.7.1 (client.id=...-admin-N)
        //   +41.4s  Kafka version 3.7.1                    ← 40.0초 단일 공백
        //   +41.4s  Started in 41.443 seconds
        //
        // ⚠️ 리스너(spring.kafka.listener.auto-startup)를 끄는 것으로는 해결되지 않는다. 실제로
        // 그걸 먼저 시도했고 **NetworkClient 로그가 34% 줄었는데 시간은 0.7%만 줄었다** —
        // 멈추는 주체가 리스너가 아니라 admin 클라이언트이기 때문이다.
        //
        // Kafka를 실제로 쓰는 테스트 4개는 이 베이스를 상속하지 않으므로 토픽 생성이 유지된다.
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
     * 컨테이너를 공유하는 대신 <b>상태는 매 테스트마다 초기화</b>한다(상위 @BeforeEach가 먼저 실행되므로
     * 하위 클래스의 시드보다 앞선다). 이렇게 해야 클래스 간 데이터가 새지 않는다.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetSharedState() {
        TestContainers.reset(dataSource, redisForReset);
    }
}
