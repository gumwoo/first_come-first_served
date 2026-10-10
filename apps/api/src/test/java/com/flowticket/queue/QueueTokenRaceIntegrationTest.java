package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.queue.service.QueueService;
import com.flowticket.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 재발급(TAKEOVER)·이탈(LEAVE) 경합(Testcontainers Redis) — 한 회원의 활성 대기 토큰은 하나다.
 * - 죽은 토큰을 동시에 여러 요청이 회수해도 새 토큰은 하나만 생긴다(TAKEOVER의 유저키 CAS).
 * - 이탈은 유저키가 아직 그 토큰을 가리킬 때만 지운다 — 그 사이 받은 새 토큰의 유저키를 지우지 않는다(LEAVE의 CAS).
 */
@SpringBootTest
class QueueTokenRaceIntegrationTest extends IntegrationTestSupport {

    @Autowired QueueService queueService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired EventRepository eventRepository;

    private Long EVENT;

    @BeforeEach
    void 판매중_이벤트를_만든다() {
        EVENT = eventRepository.save(Event.builder()
                .kopisId("QUEUE-RACE").title("경합").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
    }

    @Test
    void 죽은_토큰을_동시에_재발급해도_새_토큰은_하나만_생긴다() throws Exception {
        long user = 2000L;
        String dead = queueService.issue(user, EVENT).token();
        redisTemplate.opsForZSet().remove(waitKey(), dead); // 대기열에서 빠진 죽은 토큰(스냅샷이 EXPIRED → isReusable=false)

        Set<String> tokens = concurrently(30, () -> queueService.issue(user, EVENT).token());

        assertThat(tokens).hasSize(1); // 모든 요청이 같은 새 토큰을 받는다
        String fresh = tokens.iterator().next();
        assertThat(fresh).isNotEqualTo(dead);
        assertThat(redisTemplate.opsForValue().get(userKey(user))).isEqualTo(fresh);
        assertThat(redisTemplate.opsForZSet().zCard(waitKey())).isEqualTo(1L); // 대기열에도 하나만 선다
        assertThat(redisTemplate.hasKey("queue:token:" + dead)).isFalse(); // 옛 메타는 정리됨
    }

    @Test
    void 이탈은_그사이_바뀐_유저키를_지우지_않는다() {
        long user = 2100L;
        String t1 = queueService.issue(user, EVENT).token();
        // 이탈이 메타를 읽은 직후 같은 회원이 새 토큰을 받은 상황을 결정적으로 만든다.
        redisTemplate.opsForValue().set(userKey(user), "newer-token");

        queueService.leave(t1, user);

        assertThat(redisTemplate.opsForValue().get(userKey(user))).isEqualTo("newer-token");
        assertThat(redisTemplate.opsForZSet().score(waitKey(), t1)).isNull();
        assertThat(redisTemplate.hasKey("queue:token:" + t1)).isFalse();
    }

    @Test
    void 이탈과_재발급이_겹쳐도_대기열의_토큰은_모두_유저키가_가리킨다() throws Exception {
        // 예전에는 이탈이 유저키를 무조건 지워, 겹친 재발급의 새 토큰이 유저키 없이 대기열에 남았다
        // → 다음 진입이 SET NX에 성공해 같은 회원이 줄을 두 번 섰다.
        for (int i = 0; i < 30; i++) {
            long user = 2200L + i;
            String t = queueService.issue(user, EVENT).token();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> leave = pool.submit(() -> { await(start); queueService.leave(t, user); return null; });
            Future<?> reissue = pool.submit(() -> { await(start); return queueService.issue(user, EVENT).token(); });
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            leave.get(); // 작업 안에서 난 예외를 테스트 실패로 올린다
            reissue.get();

            Set<String> waiting = redisTemplate.opsForZSet().range(waitKey(), 0, -1);
            String owned = redisTemplate.opsForValue().get(userKey(user));
            long mine = waiting.stream()
                    .filter(tok -> String.valueOf(user).equals(redisTemplate.opsForHash().get("queue:token:" + tok, "userId")))
                    .count();
            assertThat(mine).as("회원 %d의 대기 토큰 수", user).isLessThanOrEqualTo(1);
            if (mine == 1) {
                String waitingToken = waiting.stream()
                        .filter(tok -> String.valueOf(user).equals(redisTemplate.opsForHash().get("queue:token:" + tok, "userId")))
                        .findFirst().orElseThrow();
                assertThat(owned).as("대기 중인 토큰은 유저키가 가리켜야 한다").isEqualTo(waitingToken);
            }
        }
    }

    private String waitKey() {
        return "queue:wait:" + EVENT;
    }

    private String userKey(long user) {
        return "queue:user:" + EVENT + ":" + user;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<String> concurrently(int threads, java.util.concurrent.Callable<String> op) throws Exception {
        Set<String> out = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                await(start);
                out.add(op.call());
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        for (Future<?> f : futures) {
            f.get(); // 작업 안에서 난 예외(예: 경합 재시도 초과)를 테스트 실패로 올린다
        }
        return out;
    }
}
