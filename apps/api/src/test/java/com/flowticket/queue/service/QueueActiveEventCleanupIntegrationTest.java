package com.flowticket.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 활성 공연 정리(Testcontainers Redis) — 빈 공연만 활성 목록에서 빼고, 확인과 제거 사이에 진입이 끼어도 빼지 않는다.
 * 예전에는 스냅숏으로 "대기 0·입장 0"을 확인한 뒤 SREM을 따로 해, 그 사이 진입한 대기자가 있는 공연이 목록에서 빠졌다
 * (승격 워커가 그 공연을 돌지 않아 다음 진입 전까지 승격되지 않음).
 */
@SpringBootTest
class QueueActiveEventCleanupIntegrationTest extends IntegrationTestSupport {

    private static final String ACTIVE = "queue:active-events";

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired EventRepository eventRepository;

    private Long EVENT;

    @BeforeEach
    void 판매중_이벤트를_만든다() {
        EVENT = eventRepository.save(Event.builder()
                .kopisId("QUEUE-ACTIVE").title("활성 정리").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
    }

    @Test
    void 대기도_입장도_없는_공연은_활성_목록에서_빠진다() {
        redisTemplate.opsForSet().add(ACTIVE, String.valueOf(EVENT));

        admissionService.runOnce();

        assertThat(redisTemplate.opsForSet().isMember(ACTIVE, String.valueOf(EVENT))).isFalse();
    }

    @Test
    void 확인_뒤_제거_전에_진입이_끼면_빼지_않는다() {
        // 예전 순서를 결정적으로 재현한다: ① 비어 있음을 확인 ② 그 사이 신규 진입 ③ 제거.
        redisTemplate.opsForSet().add(ACTIVE, String.valueOf(EVENT));
        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isZero(); // ① 비어 있음
        String token = queueService.issue(5000L, EVENT).token();                      // ② 진입(ZADD·SADD)

        boolean retired = admissionService.retireIfEmpty(EVENT);                      // ③ 제거 시도

        assertThat(retired).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE, String.valueOf(EVENT))).isTrue();
        // 목록에 남아 있으니 다음 틱에 승격된다.
        admissionService.runOnce();
        assertThat(redisTemplate.opsForZSet().score("queue:admitexp:" + EVENT, token)).isNotNull();
    }

    @Test
    void 입장자가_남아_있으면_대기가_0이어도_빼지_않는다() {
        queueService.issue(5100L, EVENT);
        admissionService.runOnce(); // 승격 → 대기 0, 입장 카운터 1

        assertThat(redisTemplate.opsForZSet().zCard("queue:wait:" + EVENT)).isZero();
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE, String.valueOf(EVENT))).isTrue();
    }
}
