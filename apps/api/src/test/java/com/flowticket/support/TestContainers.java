package com.flowticket.support;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 비-Kafka 통합테스트가 공유하는 컨테이너(싱글톤).
 *
 * <p>{@code @Container}는 클래스 단위 수명이라 클래스마다 컨테이너 세트가 뜨고 내려간다.
 * 여기서는 {@code @Container} 없이 static 초기화로 직접 기동해 JVM 수명 동안 공유한다
 * (정리는 Testcontainers Ryuk이 담당). Kafka 테스트는 각자 띄운다. 아래 문단 참고.
 *
 * <p>데이터 격리는 컨테이너 재기동이 아니라 테스트별 초기화로 지킨다.
 * "컨테이너 수명은 길게, 데이터 상태는 매번 초기화".
 *
 * <p>Kafka 테스트는 공유 대상이 아니다. 토픽·컨슈머 그룹·오프셋이 얽혀(다른 클래스가 남긴
 * 메시지를 새 그룹이 earliest부터 다시 소비) 실패 원인이 불투명해진다. 수가 적어 각자 브로커를
 * 띄우는 편이 명확하다.
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

    private static final String TRUNCATE_ALL = """
            do $$
            declare stmt text;
            begin
              select string_agg(format('truncate table %I restart identity cascade', tablename), '; ')
                into stmt
                from pg_tables
               where schemaname = 'public' and tablename not in ('flyway_schema_history', 'alert_settings');
              if stmt is not null then execute stmt; end if;
            end $$;
            """;

    /** 이 DB에 붙어 있는 다른 세션들: TRUNCATE가 막혔을 때 "누가 잡고 있나"를 남긴다. */
    private static final String BLOCKERS = """
            select pid, state, wait_event_type, wait_event,
                   coalesce(to_char(now() - xact_start, 'MI:SS'), '-') as xact_age,
                   left(replace(coalesce(query, ''), chr(10), ' '), 160) as q
              from pg_stat_activity
             where datname = current_database() and pid <> pg_backend_pid()
             order by xact_start nulls last
            """;

    /**
     * 모든 테스트 앞에서 상태를 비운다. 컨테이너를 공유하는 대신 데이터는 매번 초기화한다.
     *
     * <p>테이블별 {@code deleteAll()}로는 부족했다: JPA가 삭제를 지연 플러시하면서 다른 테스트가 남긴
     * 자식 행(seats·orders 등) 때문에 FK 위반이 뒤늦게 터졌다. 의존 순서를 신경 쓰지 않도록
     * {@code TRUNCATE ... CASCADE}로 한 번에 비운다.
     * 단 마이그레이션이 시드하는 테이블(alert_settings)은 지우면 기본값이 사라지므로 제외한다.
     */
    public static void reset(javax.sql.DataSource dataSource,
                             org.springframework.data.redis.core.StringRedisTemplate redis) {
        truncateAll(dataSource);
        // 팩토리에서 받은 연결은 호출자가 닫는다. Lettuce 기본 설정에서는 네이티브 연결이 공유돼
        // 실제 누수로 이어지진 않지만, 매 테스트마다 도는 코드라 자원 수명을 명시해 둔다.
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    /**
     * TRUNCATE는 모든 테이블에 ACCESS EXCLUSIVE를 요구한다. 다른 세션이 락을 쥐고 있으면
     * 기본값(lock_timeout=0)에서는 무기한 대기라 다음 클래스가 멈추고, 진짜 원인과
     * 무관한 테스트가 실패한 것처럼 보인다. 그래서 시간을 끊고 그 순간의 세션 목록을 박제한다.
     *
     * <p>상대 세션이 "waits"면 데드락(살아 있는 DML, 예: 스케줄러 스윕)이고, idle in transaction이면
     * 누수된 트랜잭션이다. 스냅샷은 실패한 뒤에 찍히므로 락을 쥐던 세션이 그 사이 정리됐을 수 있다.
     */
    private static void truncateAll(javax.sql.DataSource dataSource) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
                    try (java.sql.Statement st = conn.createStatement()) {
                        // SET은 세션 단위다. 이 커넥션은 풀로 돌아가므로 finally에서 반드시 되돌린다.
                        st.execute("set lock_timeout = '20s'");
                        try {
                            st.execute(TRUNCATE_ALL);
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(
                                    "테스트 초기화 TRUNCATE 실패: 백그라운드 스케줄러 DML과의 데드락이 가장 유력하다.\n"
                                            + "(누수된 트랜잭션이 원인이라면 아래 목록에 idle in transaction으로 나타난다)\n"
                                            + blockers(st), e);
                        } finally {
                            st.execute("set lock_timeout = default");
                        }
                    }
                    return null;
                });
    }

    private static String blockers(java.sql.Statement st) {
        StringBuilder sb = new StringBuilder("[pg_stat_activity]\n");
        try (java.sql.ResultSet rs = st.executeQuery(BLOCKERS)) {
            while (rs.next()) {
                sb.append(String.format("  pid=%s state=%s wait=%s/%s xact_age=%s q=%s%n",
                        rs.getString("pid"), rs.getString("state"), rs.getString("wait_event_type"),
                        rs.getString("wait_event"), rs.getString("xact_age"), rs.getString("q")));
            }
        } catch (java.sql.SQLException e) {
            sb.append("  (조회 실패: ").append(e.getMessage()).append(")");
        }
        return sb.toString();
    }
}
