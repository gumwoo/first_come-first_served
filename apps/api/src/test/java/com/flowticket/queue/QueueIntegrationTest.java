package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 대기열 핵심 불변식(Testcontainers Redis): 1인1토큰, 순번 유일, 정원 초과 없음(원자 승격),
 * 만료 슬롯 회수. capacity=3, 승격 워커는 사실상 비활성(수동 호출로 결정적).
 */
@SpringBootTest
@Testcontainers
class QueueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redisContainer::getHost);
        r.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        r.add("queue.capacity", () -> "3");
        r.add("queue.admit-ttl", () -> "1");            // 만료 회수 테스트용(1초)
        r.add("queue.admit-interval-ms", () -> "3600000"); // 워커 자동 실행 사실상 비활성
    }

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;

    private static final Long EVENT = 42L;

    @BeforeEach
    void flush() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void 같은_유저_2회발급은_동일_토큰() {
        String t1 = queueService.issue(7L, EVENT).token();
        String t2 = queueService.issue(7L, EVENT).token();
        assertThat(t2).isEqualTo(t1); // 1인 1토큰
    }

    @Test
    void 같은_유저_동시발급도_토큰이_하나만_생긴다() throws Exception {
        int threads = 20;
        var tokens = ConcurrentHashMap.<String>newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    tokens.add(queueService.issue(999L, EVENT).token());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(tokens).hasSize(1); // SET NX 원자 예약 → 1인1토큰
        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isEqualTo(1L);
    }

    @Test
    void 나가기는_대기열에서_제거한다() {
        String token = queueService.issue(500L, EVENT).token();
        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isEqualTo(1L);

        queueService.leave(token);

        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isEqualTo(0L);
        assertThat(redisTemplate.hasKey("queue:token:" + token)).isFalse();
    }

    @Test
    void 입장상태_나가기는_슬롯을_반환한다() {
        String token = queueService.issue(600L, EVENT).token();
        admissionService.admit(EVENT);
        assertThat(admitCount()).isEqualTo(1);

        queueService.leave(token);

        assertThat(admitCount()).isEqualTo(0); // 입장 슬롯 반환
    }

    @Test
    void 동시_leave는_admitCount를_음수로_만들지_않는다() throws Exception {
        // 입장 슬롯 반환을 Lua로 원자화 → admitExp에서 실제 제거한 요청만 DECR(이중 차감 방지).
        String token = queueService.issue(700L, EVENT).token();
        admissionService.admit(EVENT);
        assertThat(admitCount()).isEqualTo(1);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    queueService.leave(token); // 같은 토큰 동시 이탈
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(admitCount()).isEqualTo(0); // 정확히 1회만 반환(음수 아님)
    }

    @Test
    void N명_동시발급_토큰_유일하고_순번_일관() throws Exception {
        int n = 30;
        var tokens = ConcurrentHashMap.<String>newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            long userId = 1000L + i;
            pool.submit(() -> {
                try {
                    start.await();
                    tokens.add(queueService.issue(userId, EVENT).token());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(tokens).hasSize(n); // 토큰 유일
        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isEqualTo((long) n);
    }

    @Test
    void 입장창이_만료된_토큰으로_재진입하면_새_순번을_받는다() throws Exception {
        // 버그 재현·수정 근거: 입장 후 미진행으로 만료된 토큰이 유저키에 남아
        // 1인1토큰 때문에 재예매가 막히던 것을 self-heal(죽은 토큰 정리 후 재발급)로 해결.
        Long user = 800L;
        String t1 = queueService.issue(user, EVENT).token();
        admissionService.admit(EVENT); // t1 입장(ADMITTED)
        assertThat(redisTemplate.hasKey("queue:admit:" + t1)).isTrue();

        Thread.sleep(2000);            // admit-ttl(1s) 경과 → 입장창 만료
        admissionService.reclaim(EVENT); // 슬롯 회수(유저키·메타는 남음 = 죽은 토큰)

        var reissued = queueService.issue(user, EVENT); // 재진입
        assertThat(reissued.token()).isNotEqualTo(t1);  // 죽은 토큰 대신 새 토큰
        assertThat(reissued.status()).isEqualTo("WAITING");
        assertThat(redisTemplate.opsForZSet().rank("queue:wait:" + EVENT, reissued.token()))
                .isNotNull(); // 다시 대기열에 등록됨
    }

    @Test
    void 입장상태에서_재발급하면_같은_토큰이_유지된다() {
        // 상태기계 분기: ADMITTED 토큰은 재발급 시 그대로 재사용(1인1토큰) — 죽은 토큰만 새로 발급.
        String t1 = queueService.issue(50L, EVENT).token();
        admissionService.admit(EVENT);
        assertThat(redisTemplate.hasKey("queue:admit:" + t1)).isTrue();

        var again = queueService.issue(50L, EVENT);
        assertThat(again.token()).isEqualTo(t1);          // 살아있는 입장 토큰 유지
        assertThat(again.status()).isEqualTo("ADMITTED");
    }

    @Test
    void 이탈_후_재발급하면_새_토큰을_받는다() {
        // 상태기계 분기: leave로 소유권이 정리되면 재진입은 새 순번(WAITING)이어야 함.
        String t1 = queueService.issue(60L, EVENT).token();
        queueService.leave(t1);

        var reissued = queueService.issue(60L, EVENT);
        assertThat(reissued.token()).isNotEqualTo(t1);    // 새 토큰
        assertThat(reissued.status()).isEqualTo("WAITING");
        assertThat(redisTemplate.opsForZSet().rank("queue:wait:" + EVENT, reissued.token())).isNotNull();
    }

    @Test
    void 원자_승격은_정원을_초과하지_않는다() {
        for (int i = 0; i < 10; i++) {
            queueService.issue(2000L + i, EVENT); // 10명 대기
        }
        int first = admissionService.admit(EVENT);
        int second = admissionService.admit(EVENT); // 이미 정원 → 0

        assertThat(first).isEqualTo(3);  // capacity
        assertThat(second).isEqualTo(0);
        assertThat(admitCount()).isEqualTo(3); // 절대 초과 없음
    }

    @Test
    void 만료된_입장은_회수되어_슬롯이_반환된다() throws Exception {
        for (int i = 0; i < 6; i++) {
            queueService.issue(3000L + i, EVENT);
        }
        assertThat(admissionService.admit(EVENT)).isEqualTo(3);
        assertThat(admitCount()).isEqualTo(3);

        Thread.sleep(2000); // admit-ttl(1s) 경과
        List<String> reclaimed = admissionService.reclaim(EVENT);
        assertThat(reclaimed).hasSize(3);
        assertThat(admitCount()).isEqualTo(0); // 슬롯 반환

        assertThat(admissionService.admit(EVENT)).isEqualTo(3); // 다음 3명 승격
    }

    @Test
    void 비교후행동_naive_승격은_정원을_초과한다() throws Exception {
        // IMP-004 before 근거: 원자화하지 않으면 동시 실행 시 over-admit이 발생함을 재현.
        for (int i = 0; i < 10; i++) {
            queueService.issue(4000L + i, EVENT);
        }
        int cap = 3, threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // naive: GET → 비교 → (지연) → POP → INCR (비원자)
                    String c = redisTemplate.opsForValue().get("queue:admitcount:" + EVENT);
                    long count = c == null ? 0 : Long.parseLong(c);
                    if (count < cap) {
                        Thread.sleep(20); // 레이스 창 확대
                        redisTemplate.opsForZSet().popMin("queue:wait:" + EVENT);
                        redisTemplate.opsForValue().increment("queue:admitcount:" + EVENT);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(admitCount()).isGreaterThan(cap); // 정원 초과 발생(원자화 필요성 입증)
    }

    @Test
    void 비원자_GET후SET은_같은유저_중복토큰을_만든다() throws Exception {
        // IMP-007 before 근거: SET NX 없이 GET→없으면 SET이면 같은 유저 동시 요청에 중복 발급.
        String userKey = "queue:user:" + EVENT + ":888";
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // naive: GET → 없으면 (지연) → 토큰 생성 + wait 등록 + SET (비원자)
                    if (redisTemplate.opsForValue().get(userKey) == null) {
                        Thread.sleep(20); // 레이스 창 확대
                        String tok = java.util.UUID.randomUUID().toString();
                        redisTemplate.opsForZSet().add("queue:wait:" + EVENT, tok, System.nanoTime());
                        redisTemplate.opsForValue().set(userKey, tok);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        Long dup = redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT);
        assertThat(dup).isGreaterThan(1L); // 같은 유저인데 대기열에 여러 토큰(중복 발급)
    }

    private long admitCount() {
        String c = redisTemplate.opsForValue().get("queue:admitcount:" + EVENT);
        return c == null ? 0 : Long.parseLong(c);
    }
}
