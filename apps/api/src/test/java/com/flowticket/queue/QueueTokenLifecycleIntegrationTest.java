package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 대기 토큰 수명주기(Testcontainers Redis) — 사용자 결정: 폴링할 때마다 token-ttl(30분) 연장, 발급 뒤 절대 상한 6시간.
 * - 대기 중 폴링은 남은 수명이 절반 아래일 때 메타·유저키를 token-ttl로 늘린다(유저키는 같은 토큰을 가리킬 때만).
 * - 발급 시각 + 절대 상한을 넘겨서는 늘리지 않는다. 발급 시각이 없는 옛 토큰은 늘리지 않는다.
 * - 수명이 끝난(메타 없는) 토큰은 승격 직전 대기열 앞에서 빠져, 입장 슬롯을 쥐지 않는다.
 */
@TestPropertySource(properties = {"queue.capacity=2"})
@SpringBootTest
class QueueTokenLifecycleIntegrationTest extends IntegrationTestSupport {

    private static final long TOKEN_TTL = 1800;
    private static final long MAX_LIFETIME = 21600;

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired EventRepository eventRepository;
    @Autowired MeterRegistry meterRegistry;

    private Long EVENT;

    @BeforeEach
    void 판매중_이벤트를_만든다() {
        EVENT = eventRepository.save(Event.builder()
                .kopisId("QUEUE-LIFE").title("수명").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
    }

    @Test
    void 대기_중_폴링하면_메타와_유저키_수명이_늘어난다() {
        String token = queueService.issue(3000L, EVENT).token();
        shorten(token, 3000L, 100);

        assertThat(queueService.status(token).status()).isEqualTo("WAITING");

        assertThat(ttl(meta(token))).isGreaterThan(TOKEN_TTL - 60);
        assertThat(ttl(userKey(3000L))).isGreaterThan(TOKEN_TTL - 60);
    }

    @Test
    void 남은_수명이_절반_이상이면_늘리지_않는다() {
        String token = queueService.issue(3010L, EVENT).token();
        redisTemplate.expire(meta(token), Duration.ofSeconds(1500)); // 절반(900)보다 많이 남음

        queueService.status(token);

        assertThat(ttl(meta(token))).isLessThanOrEqualTo(1500);
    }

    @Test
    void 유저키가_다른_토큰을_가리키면_유저키는_늘리지_않는다() {
        String token = queueService.issue(3020L, EVENT).token();
        redisTemplate.opsForValue().set(userKey(3020L), "other-token", Duration.ofSeconds(100));
        redisTemplate.expire(meta(token), Duration.ofSeconds(100));

        queueService.status(token);

        assertThat(ttl(meta(token))).isGreaterThan(TOKEN_TTL - 60);
        assertThat(redisTemplate.opsForValue().get(userKey(3020L))).isEqualTo("other-token");
        assertThat(ttl(userKey(3020L))).isLessThanOrEqualTo(100);
    }

    @Test
    void 절대_상한을_넘으면_늘리지_않는다() {
        String token = queueService.issue(3030L, EVENT).token();
        issuedAgo(token, MAX_LIFETIME + 10);
        shorten(token, 3030L, 100);

        queueService.status(token);

        assertThat(ttl(meta(token))).isLessThanOrEqualTo(100);
        assertThat(ttl(userKey(3030L))).isLessThanOrEqualTo(100);
    }

    @Test
    void 절대_상한_근처에서는_상한까지만_늘린다() {
        String token = queueService.issue(3040L, EVENT).token();
        issuedAgo(token, MAX_LIFETIME - 300); // 상한까지 약 300초
        shorten(token, 3040L, 100);

        queueService.status(token);

        assertThat(ttl(meta(token))).isBetween(200L, 300L);
    }

    @Test
    void 발급_시각이_없는_옛_토큰은_늘리지_않는다() {
        String token = queueService.issue(3050L, EVENT).token();
        redisTemplate.opsForHash().delete(meta(token), "issuedAt");
        shorten(token, 3050L, 100);

        assertThat(queueService.status(token).status()).isEqualTo("WAITING");

        assertThat(ttl(meta(token))).isLessThanOrEqualTo(100);
    }

    @Test
    void 수명이_끝난_토큰은_승격되지_않고_대기열에서_빠진다() {
        String dead = queueService.issue(3060L, EVENT).token(); // 앞 순번
        String alive = queueService.issue(3061L, EVENT).token();
        redisTemplate.delete(meta(dead)); // 폴링이 끊겨 수명이 끝난 상태
        double before = meterRegistry.get("flowticket.queue.purged.dead").counter().count();

        int admitted = admissionService.admit(EVENT);

        assertThat(admitted).isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().score("queue:admitexp:" + EVENT, dead)).isNull();
        assertThat(redisTemplate.opsForZSet().score("queue:wait:" + EVENT, dead)).isNull();
        assertThat(redisTemplate.opsForZSet().score("queue:admitexp:" + EVENT, alive)).isNotNull();
        assertThat(redisTemplate.opsForValue().get("queue:admitcount:" + EVENT)).isEqualTo("1");
        assertThat(meterRegistry.get("flowticket.queue.purged.dead").counter().count()).isEqualTo(before + 1);
    }

    @Test
    void 앞쪽_죽은_토큰을_한_틱에_다_못_치우면_그_틱은_승격하지_않는다() {
        // 정원 2 → 한 회에 앞쪽 2개씩, 최대 5회 = 10개까지 본다. 죽은 토큰 11개 + 산 토큰 1개.
        for (int i = 0; i < 11; i++) {
            String dead = queueService.issue(3200L + i, EVENT).token();
            redisTemplate.delete(meta(dead));
        }
        String alive = queueService.issue(3299L, EVENT).token();

        assertThat(admissionService.admit(EVENT)).isZero(); // 죽은 토큰을 승격하지 않는다
        assertThat(redisTemplate.opsForZSet().zCard("queue:admitexp:" + EVENT)).isZero();

        assertThat(admissionService.admit(EVENT)).isEqualTo(1); // 다음 틱이 나머지를 치우고 산 토큰을 올린다
        assertThat(redisTemplate.opsForZSet().score("queue:admitexp:" + EVENT, alive)).isNotNull();
        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isZero();
    }

    @Test
    void 입장한_토큰의_폴링은_대기_연장을_하지_않는다() {
        String token = queueService.issue(3080L, EVENT).token();
        admissionService.admit(EVENT); // 메타는 승격 때 입장창 + 60초 이상으로 늘어난다(EXPIRE GT)
        redisTemplate.expire(userKey(3080L), Duration.ofSeconds(100));

        assertThat(queueService.status(token).status()).isEqualTo("ADMITTED");

        assertThat(ttl(userKey(3080L))).isLessThanOrEqualTo(100); // 대기 연장 분기를 타지 않는다
    }

    @Test
    void 발급은_메타에_발급_시각을_남긴다() {
        long now = Instant.now().getEpochSecond();
        String token = queueService.issue(3070L, EVENT).token();

        Object issuedAt = redisTemplate.opsForHash().get(meta(token), "issuedAt");
        assertThat(issuedAt).isNotNull();
        assertThat(Long.parseLong((String) issuedAt)).isBetween(now - 5, now + 5);
    }

    private void shorten(String token, long user, long seconds) {
        redisTemplate.expire(meta(token), Duration.ofSeconds(seconds));
        redisTemplate.expire(userKey(user), Duration.ofSeconds(seconds));
    }

    private void issuedAgo(String token, long seconds) {
        redisTemplate.opsForHash().put(meta(token), "issuedAt", String.valueOf(Instant.now().getEpochSecond() - seconds));
    }

    private long ttl(String key) {
        Long t = redisTemplate.getExpire(key);
        return t == null ? -2 : t;
    }

    private String meta(String token) {
        return "queue:token:" + token;
    }

    private String userKey(long user) {
        return "queue:user:" + EVENT + ":" + user;
    }
}
