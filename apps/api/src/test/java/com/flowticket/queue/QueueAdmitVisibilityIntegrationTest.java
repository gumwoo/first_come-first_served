package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * [[TS-024]] 회귀 — 승격 커밋이 Lua 밖으로 새지 않는지 본다.
 *
 * <p>승격은 pop + admitcount 증가 + admitExp 등록까지 <b>한 Lua로 확정</b>되고,
 * {@code queue:admit:{token}} 키와 SSE 알림은 그 뒤에 붙는 부수 작업이다. 예전에는 pop과
 * 카운트 증가까지만 원자였고 admitExp 등록이 Java 루프여서 두 가지가 샜다.
 *
 * <ul>
 *   <li>① wait에서도 빠지고 입장 표시도 없는 창 → 상태 조회가 EXPIRED로 떨어졌다.
 *       2026-08-12 스파이크 실측에서 3,000명 중 10명이 진입 응답으로 EXPIRED를 받았다.</li>
 *   <li>② 그 창에서 Pod가 죽으면 admitcount만 오른 채 admitExp에 없어 <b>정원이 영구 누수</b>된다.
 *       카운트를 줄이는 경로(RECLAIM/LEAVE)가 둘 다 admitExp를 근거로 움직이기 때문이다.</li>
 * </ul>
 *
 * <p>{@code admit-ttl}을 넉넉히 둔다 — {@link QueueIntegrationTest}는 1초라서
 * "만료 전인가" 판정이 초 경계에서 뒤집힐 수 있다.
 */
@TestPropertySource(properties = {"queue.capacity=3", "queue.admit-ttl=60"})
@SpringBootTest
class QueueAdmitVisibilityIntegrationTest extends IntegrationTestSupport {

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;

    @Autowired EventRepository eventRepository;

    /** 발급 게이트가 실재하는 ON_SALE 이벤트를 요구한다 — {@link QueueIntegrationTest} 주석 참고. */
    private Long EVENT;

    @BeforeEach
    void 판매중_이벤트를_만든다() {
        EVENT = eventRepository.save(Event.builder()
                .kopisId("QUEUE-ADMIT").title("판매중").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
    }

    @Test
    void 승격이_확정됐으면_admit키가_없어도_ADMITTED로_판정한다() {
        String token = queueService.issue(900L, EVENT).token();
        admissionService.admit(EVENT);
        assertThat(redisTemplate.hasKey("queue:admit:" + token)).isTrue();

        // 승격은 확정됐는데 표시가 아직 없는 창을 결정적으로 재현한다.
        redisTemplate.delete("queue:admit:" + token);

        assertThat(queueService.status(token).status()).isEqualTo("ADMITTED");
        // 좌석 게이트도 같은 규칙이어야 한다 — 한쪽만 고치면
        // "대기열은 입장이라는데 좌석은 거절"이라는 더 나쁜 불일치가 생긴다.
        assertThat(queueService.isAdmitted(token, EVENT)).isTrue();
    }

    @Test
    void 승격은_카운트와_만료등록이_함께_움직인다() {
        for (long u = 910L; u < 913L; u++) {
            queueService.issue(u, EVENT);
        }

        int admitted = admissionService.admit(EVENT);

        String count = redisTemplate.opsForValue().get("queue:admitcount:" + EVENT);
        Long registered = redisTemplate.opsForZSet().zCard("queue:admitexp:" + EVENT);
        assertThat(admitted).isPositive();
        assertThat(Long.parseLong(count)).isEqualTo(admitted);
        // 이 둘이 어긋나면 그만큼의 슬롯이 영원히 회수되지 않는다.
        assertThat(registered).isEqualTo((long) admitted);
    }

    @Test
    void 만료_회수는_카운트와_만료등록을_함께_되돌린다() {
        for (long u = 920L; u < 923L; u++) {
            queueService.issue(u, EVENT);
        }
        int admitted = admissionService.admit(EVENT);
        assertThat(admitted).isPositive();

        // 만료 시각을 과거로 밀어 회수 대상으로 만든다(60초를 기다리지 않는다).
        var registeredTokens = redisTemplate.opsForZSet().range("queue:admitexp:" + EVENT, 0, -1);
        assertThat(registeredTokens).hasSize(admitted);
        registeredTokens.forEach(t -> redisTemplate.opsForZSet().add("queue:admitexp:" + EVENT, t, 0));

        assertThat(admissionService.reclaim(EVENT)).hasSize(admitted);

        String count = redisTemplate.opsForValue().get("queue:admitcount:" + EVENT);
        Long registered = redisTemplate.opsForZSet().zCard("queue:admitexp:" + EVENT);
        assertThat(Long.parseLong(count)).isZero();  // 슬롯이 온전히 돌아왔다
        assertThat(registered).isZero();
    }
}
