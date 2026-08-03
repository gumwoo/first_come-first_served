package com.flowticket.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 스위트 전체가 공유하는 컨테이너(싱글톤).
 *
 * <p>예전에는 통합테스트 클래스마다 {@code @Container}로 자기 Postgres/Redis를 띄웠다.
 * {@code @Container}는 <b>클래스 단위</b> 수명이라 26개 클래스 = 26세트가 뜨고 내려가며,
 * CI 백엔드 잡 23분의 대부분이 여기서 나왔다. 여기서는 {@code @Container} 없이 static 초기화로
 * 직접 기동해 <b>JVM 수명</b> 동안 공유한다(정리는 Testcontainers Ryuk이 담당).
 *
 * <p>데이터 격리는 컨테이너 재기동이 아니라 <b>테스트별 초기화</b>(@BeforeEach deleteAll/flushAll)로 지킨다 —
 * "컨테이너 수명은 길게, 데이터 상태는 매번 초기화".
 */
public final class TestContainers {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private TestContainers() {
    }

    /** Postgres·Redis 접속 정보. 컨테이너에서 온 값이라 정적 프로퍼티로는 표현할 수 없다. */
    public static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /**
     * 모든 테스트 앞에서 상태를 비운다 — 컨테이너를 공유하는 대신 <b>데이터는 매번 초기화</b>한다.
     *
     * <p>테이블별 {@code deleteAll()}로는 부족했다: JPA가 삭제를 지연 플러시하면서 다른 테스트가 남긴
     * 자식 행(seats·orders 등) 때문에 FK 위반이 뒤늦게 터졌다. 의존 순서를 신경 쓰지 않도록
     * {@code TRUNCATE ... CASCADE}로 한 번에 비운다.
     * 단 <b>마이그레이션이 시드하는 테이블</b>(alert_settings)은 지우면 기본값이 사라지므로 제외한다.
     */
    public static void reset(javax.sql.DataSource dataSource,
                             org.springframework.data.redis.core.StringRedisTemplate redis) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("""
                do $$
                declare stmt text;
                begin
                  select string_agg(format('truncate table %I restart identity cascade', tablename), '; ')
                    into stmt
                    from pg_tables
                   where schemaname = 'public' and tablename not in ('flyway_schema_history', 'alert_settings');
                  if stmt is not null then execute stmt; end if;
                end $$;
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** Kafka 부트스트랩 주소. 이 메서드를 부르는 순간에만 브로커가 뜬다(아래 홀더 클래스 로딩). */
    public static String kafkaBootstrapServers() {
        return KafkaHolder.INSTANCE.getBootstrapServers();
    }

    /**
     * Kafka는 기동이 가장 무겁다(브로커 부팅+토픽 준비). 필요한 테스트에서만 켜지도록
     * 홀더 클래스로 감싸 <b>지연 초기화</b>한다 — Kafka를 안 쓰는 테스트만 도는 실행에서는 아예 안 뜬다.
     */
    private static final class KafkaHolder {
        private static final KafkaContainer INSTANCE =
                new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

        static {
            INSTANCE.start();
        }

        private KafkaHolder() {
        }
    }
}
