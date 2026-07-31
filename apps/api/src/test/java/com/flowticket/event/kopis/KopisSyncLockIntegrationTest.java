package com.flowticket.event.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * KOPIS 동기화 락이 <b>수동 경로까지</b> 막는지 검증. 예전엔 {@code @SchedulerLock}이 스케줄 메서드에만
 * 붙어 있어 수동 API가 호출하는 {@code sync()}는 락을 우회했고, 자동/수동 동기화가 겹칠 수 있었다.
 * 이제 락이 공통 진입점에 있으므로 락이 이미 잡혀 있으면 호출 자체가 건너뛰어진다(null 반환).
 */
@SpringBootTest
@Testcontainers
class KopisSyncLockIntegrationTest {

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
        r.add("queue.admit-interval-ms", () -> "3600000");
        r.add("seat.sweep-interval-ms", () -> "3600000");
        r.add("order.sweep-interval-ms", () -> "3600000");
        r.add("outbox.relay-interval-ms", () -> "3600000");
        r.add("payment.reconcile-interval-ms", () -> "3600000");
    }

    @Autowired KopisSyncService kopisSyncService;
    @Autowired LockProvider lockProvider;
    @MockBean KopisClient kopisClient; // 실제 KOPIS 호출 차단(락에 막히면 어차피 호출되지 않음)

    @Test
    void 락이_이미_잡혀있으면_수동_동기화는_실행되지_않는다() {
        // 다른 인스턴스(또는 새벽 스케줄)가 동기화 중인 상황을 재현 — 같은 락 이름을 선점.
        LockConfiguration held = new LockConfiguration(
                Instant.now(), "kopis-sync", Duration.ofSeconds(30), Duration.ZERO);
        Optional<SimpleLock> lock = lockProvider.lock(held);
        assertThat(lock).isPresent();

        try {
            Integer result = kopisSyncService.sync();

            // ShedLock이 호출을 건너뛰어 null → 컨트롤러가 409(SYNC_IN_PROGRESS)로 변환한다.
            assertThat(result).as("락 보유 중이면 동기화 본문이 실행되지 않는다").isNull();
            verify(kopisClient, never()).fetchListAll(anyString(), anyString(), anyInt(), anyInt());
        } finally {
            lock.orElseThrow().unlock();
        }
    }

    @Test
    void 락이_비어있으면_수동_동기화가_실행된다() {
        // 선점 없음 → 본문 실행(외부 호출은 목이라 0건). null이 아니어야 한다.
        Integer result = kopisSyncService.sync();

        assertThat(result).as("락을 잡을 수 있으면 실행되고 건수를 반환한다").isNotNull();
        verify(kopisClient, org.mockito.Mockito.atLeastOnce())
                .fetchListAll(anyString(), anyString(), anyInt(), anyInt());
    }
}
