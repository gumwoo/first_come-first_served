package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * TS-024 회귀: 승격 커밋이 Lua 밖으로 새지 않는지 본다.
 *
 * 승격은 pop + admitcount 증가 + admitExp 등록까지 한 Lua로 확정되고,
 * queue:admit:{token} 키와 SSE 알림은 그 뒤에 붙는 부수 작업이다. admitExp 등록이
 * Lua 밖에 있으면 두 가지가 샌다.
 *
 *   - 1) wait에서도 빠지고 입장 표시도 없는 창 → 상태 조회가 EXPIRED로 떨어진다.
 *   - 2) 그 창에서 Pod가 죽으면 admitcount만 오른 채 admitExp에 없어 정원이 영구 누수된다.
 *       카운트를 줄이는 경로(RECLAIM/LEAVE)가 둘 다 admitExp를 근거로 움직이기 때문이다.
 *
 * admit-ttl을 넉넉히 둔다. QueueIntegrationTest는 1초라서
 * "만료 전인가" 판정이 초 경계에서 뒤집힐 수 있다.
 */
@TestPropertySource(properties = {"queue.capacity=3", "queue.admit-ttl=60"})
@SpringBootTest
class QueueAdmitVisibilityIntegrationTest extends IntegrationTestSupport {

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MeterRegistry meterRegistry;

    @Autowired EventRepository eventRepository;

    /** 발급 게이트가 실재하는 ON_SALE 이벤트를 요구한다. QueueIntegrationTest 주석 참고. */
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
        // 좌석 게이트도 같은 규칙이어야 한다. 한쪽만 고치면
        // "대기열은 입장이라는데 좌석은 거절"이라는 더 나쁜 불일치가 생긴다.
        assertThat(queueService.isAdmitted(token, EVENT)).isTrue();
    }

    /**
     * 회수 뒤에도 admit 키가 남는 창(loadtest-100k-plan §3.3)에서 게이트가 통과시키지 않는지 본다.
     * 회수는 admitExp만 지우고 같은 틱에서 빈 슬롯을 다른 사람에게 다시 승격하므로, 여기서 통과시키면 실효 입장자가
     * 정원을 넘는다. 게이트가 admitExp 원소만 받아야 over-admit 판정(admitExp 원소 수 > 정원)이 실효 입장 초과까지 덮는다.
     * 거부는 카운터에 남아 그 창이 실제로 생겼는지 볼 수 있다.
     */
    @Test
    void 회수돼_admitExp에_없는_토큰은_admit키가_남아도_거부하고_센다() {
        String token = queueService.issue(930L, EVENT).token();
        admissionService.admit(EVENT);
        // 회수가 admitExp만 지우고 admit 키는 남긴 상태를 결정적으로 만든다.
        redisTemplate.opsForZSet().remove("queue:admitexp:" + EVENT, token);
        assertThat(redisTemplate.hasKey("queue:admit:" + token)).isTrue();
        double before = meterRegistry.get("flowticket.queue.gate.fallback").counter().count();

        assertThat(queueService.isAdmitted(token, EVENT)).isFalse();

        assertThat(meterRegistry.get("flowticket.queue.gate.fallback").counter().count()).isEqualTo(before + 1);
    }

    /** 다른 이벤트로 온 회수 토큰은 이 이벤트의 실효 입장 초과 창이 아니다 — 거부하되 세지 않는다. */
    @Test
    void 다른_이벤트의_회수_토큰은_거부하고_세지_않는다() {
        String token = queueService.issue(932L, EVENT).token();
        admissionService.admit(EVENT);
        redisTemplate.opsForZSet().remove("queue:admitexp:" + EVENT, token);
        Long otherEvent = eventRepository.save(Event.builder()
                .kopisId("QUEUE-ADMIT-OTHER").title("다른 공연").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
        double before = meterRegistry.get("flowticket.queue.gate.fallback").counter().count();

        assertThat(queueService.isAdmitted(token, otherEvent)).isFalse();

        assertThat(meterRegistry.get("flowticket.queue.gate.fallback").counter().count()).isEqualTo(before);
    }

    /**
     * 만료됐지만 아직 회수 전인 토큰은 admitExp와 카운터에 남아 슬롯을 쥔 상태라 정원 초과가 아니다.
     * admit 키로 통과시키고(다음 회수 틱까지의 짧은 구간), 거부 카운터에도 세지 않는다.
     */
    @Test
    void 회수_전_만료_토큰은_admit키로_통과하고_세지_않는다() {
        String token = queueService.issue(931L, EVENT).token();
        admissionService.admit(EVENT);
        // 점수를 과거로 돌려 "만료됐지만 회수 전" 상태를 만든다. admit 키는 남아 있다.
        redisTemplate.opsForZSet().add("queue:admitexp:" + EVENT, token, 1);
        double before = meterRegistry.get("flowticket.queue.gate.fallback").counter().count();

        assertThat(queueService.isAdmitted(token, EVENT)).isTrue();

        assertThat(meterRegistry.get("flowticket.queue.gate.fallback").counter().count()).isEqualTo(before);
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
