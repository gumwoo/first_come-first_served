package com.flowticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ② ShedLock(멀티 Pod 스케줄러 중복 방지). Redis lock provider가 같은 락 이름에 대해
 * 상호배제(한 번에 하나만 획득)를 보장하는지 검증 — @Scheduled가 Pod마다 돌아도 한 Pod만 실행되는 근거.
 */
@SpringBootTest
class SchedulerLockIntegrationTest extends IntegrationTestSupport {

    @Autowired LockProvider lockProvider;

    @Test
    void 같은_락은_한번에_하나만_획득되고_해제후_재획득된다() {
        LockConfiguration cfg = new LockConfiguration(
                Instant.now(), "sweep-x", Duration.ofSeconds(30), Duration.ZERO);

        Optional<SimpleLock> first = lockProvider.lock(cfg);
        assertThat(first).isPresent();                       // 첫 Pod 획득

        // 다른 Pod가 같은 틱에 잡으려 하면(락 보유 중) 실패 → 중복 실행 안 됨
        assertThat(lockProvider.lock(cfg)).isEmpty();

        first.get().unlock();                                // 실행 종료 → 해제(lockAtLeastFor=0)
        Optional<SimpleLock> reacquired = lockProvider.lock(cfg);
        assertThat(reacquired).isPresent();                  // 다음 틱은 다시 획득 가능
        reacquired.get().unlock();
    }
}
