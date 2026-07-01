package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.queue.dto.QueueTokenResponse;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import java.util.List;
import java.util.Set;
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
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("jwt.secret", () -> "integration-test-secret-0123456789-0123456789-0123456789");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        r.add("queue.capacity", () -> "3");
        r.add("queue.admit-ttl", () -> "1");            // 만료 회수 테스트용(1초)
        r.add("queue.admit-interval-ms", () -> "3600000"); // 워커 자동 실행 사실상 비활성
    }

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redis;

    private static final Long EVENT = 42L;

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void 같은_유저_2회발급은_동일_토큰() {
        String t1 = queueService.issue(7L, EVENT).token();
        String t2 = queueService.issue(7L, EVENT).token();
        assertThat(t2).isEqualTo(t1); // 1인 1토큰
    }

    @Test
    void N명_동시발급_토큰_유일하고_순번_일관() throws Exception {
        int n = 30;
        var tokens = ConcurrentHashMap.<String>newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            long userId = 1000 + i;
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
        assertThat(redis.opsForZSet().zCard("queue:wait:" + EVENT)).isEqualTo((long) n);
    }

    @Test
    void 원자_승격은_정원을_초과하지_않는다() {
        for (int i = 0; i < 10; i++) {
            queueService.issue(2000 + i, EVENT); // 10명 대기
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
            queueService.issue(3000 + i, EVENT);
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
            queueService.issue(4000 + i, EVENT);
        }
        int cap = 3, threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // naive: GET → 비교 → (지연) → POP → INCR (비원자)
                    String c = redis.opsForValue().get("queue:admitcount:" + EVENT);
                    long count = c == null ? 0 : Long.parseLong(c);
                    if (count < cap) {
                        Thread.sleep(20); // 레이스 창 확대
                        redis.opsForZSet().popMin("queue:wait:" + EVENT);
                        redis.opsForValue().increment("queue:admitcount:" + EVENT);
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

    private long admitCount() {
        String c = redis.opsForValue().get("queue:admitcount:" + EVENT);
        return c == null ? 0 : Long.parseLong(c);
    }
}
