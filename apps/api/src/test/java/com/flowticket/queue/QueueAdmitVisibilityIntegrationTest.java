package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
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
 * queue:admit:{token} 키는 그 뒤에 붙는 부수 작업이다. admitExp 등록이
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
    void 상태_조회는_입장_대기_만료를_판정하고_다음_조회_최소_대기를_준다() {
        // STATUS_LUA 한 번으로 판정한다(예전 6왕복과 같은 규칙). 정원 3: 앞 3명 입장, 나머지 대기.
        // 이 클래스(admit-ttl 60초)에 두는 이유: 입장 판정이 초 경계에서 뒤집히지 않게(클래스 주석).
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tokens.add(queueService.issue(4000L + i, EVENT).token());
        }
        admissionService.admit(EVENT);

        var admitted = queueService.status(tokens.get(0));
        assertThat(admitted.status()).isEqualTo("ADMITTED");
        assertThat(admitted.rank()).isZero();
        assertThat(admitted.total()).isEqualTo(7);
        assertThat(admitted.retryAfterMs()).isZero(); // 종료 상태 — 더 묻지 않는다

        var waiting = queueService.status(tokens.get(3));
        assertThat(waiting.status()).isEqualTo("WAITING");
        assertThat(waiting.rank()).isEqualTo(1);
        assertThat(waiting.total()).isEqualTo(7);
        assertThat(waiting.retryAfterMs()).isEqualTo(2000); // 앞쪽(정원 × 2 안)

        // 대기·입장 어디에도 없고 메타만 남은 토큰(회수된 죽은 토큰) = EXPIRED
        redisTemplate.opsForZSet().remove("queue:wait:" + EVENT, tokens.get(9));
        var expired = queueService.status(tokens.get(9));
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.retryAfterMs()).isZero();

        // 메타가 없으면(수명 만료) 오류
        assertThatThrownBy(() -> queueService.status("no-such-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.QUEUE_EXPIRED);
    }

    @Test
    void 진입_응답에도_첫_조회_최소_대기가_있다() {
        var first = queueService.issue(4100L, EVENT);
        assertThat(first.retryAfterMs()).isEqualTo(2000); // 순번 1 — 앞쪽
        admissionService.admit(EVENT);
        var again = queueService.issue(4100L, EVENT); // 입장 상태 재진입(같은 토큰)
        assertThat(again.status()).isEqualTo("ADMITTED");
        assertThat(again.retryAfterMs()).isZero();
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
        assertThat(queueService.isAdmitted(token, EVENT, 900L)).isTrue();
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

        assertThat(queueService.isAdmitted(token, EVENT, 930L)).isFalse();

        assertThat(meterRegistry.get("flowticket.queue.gate.fallback").counter().count()).isEqualTo(before + 1);
    }

    /**
     * 다른 이벤트로 온 토큰은 그 이벤트의 admitExp에 없어 거부되지만, 실효 입장 초과 창(같은 이벤트의 회수 토큰)이
     * 아니므로 세지 않는다. 원래 이벤트의 admitExp에는 그대로 두어, 소속 확인(sameEvent)만으로 갈리는 경로를 본다.
     */
    @Test
    void 다른_이벤트로_온_토큰은_거부하고_세지_않는다() {
        String token = queueService.issue(932L, EVENT).token();
        admissionService.admit(EVENT);
        assertThat(queueService.isAdmitted(token, EVENT, 932L)).isTrue();
        Long otherEvent = eventRepository.save(Event.builder()
                .kopisId("QUEUE-ADMIT-OTHER").title("다른 공연").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
        double before = meterRegistry.get("flowticket.queue.gate.fallback").counter().count();

        assertThat(queueService.isAdmitted(token, otherEvent, 932L)).isFalse();

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

        assertThat(queueService.isAdmitted(token, EVENT, 931L)).isTrue();

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

    // ── 상태 조회 · 재진입 판정 · 좌석 게이트가 같은 규칙인가(TS-047) ──────────────────────────────
    // 규칙: admitExp에 있고, 입장창이 남았거나(점수 > 지금) 아직 회수 전이라 admit 키가 남아 있으면 입장.

    @Test
    void 회수돼_admitExp에_없으면_admit키가_남아도_상태는_EXPIRED이고_게이트도_거부한다() {
        String token = queueService.issue(940L, EVENT).token();
        admissionService.admit(EVENT);
        redisTemplate.opsForZSet().add("queue:admitexp:" + EVENT, token, 0); // 점수를 과거로 → 회수 대상
        assertThat(admissionService.reclaim(EVENT)).contains(token);
        assertThat(redisTemplate.hasKey("queue:admit:" + token)).isTrue();  // 회수는 admit 키를 남긴다

        // 예전에는 상태 조회가 admit 키만 보고 ADMITTED로 답했다 — 게이트는 거부하는데 화면은 입장으로 보였다.
        assertThat(queueService.status(token).status()).isEqualTo("EXPIRED");
        assertThat(queueService.isAdmitted(token, EVENT, 940L)).isFalse();
    }

    @Test
    void 회수돼_admit키만_남은_토큰으로_재진입하면_새_토큰을_받는다() {
        String token = queueService.issue(941L, EVENT).token();
        admissionService.admit(EVENT);
        redisTemplate.opsForZSet().add("queue:admitexp:" + EVENT, token, 0);
        admissionService.reclaim(EVENT);

        // 재진입 판정도 같은 규칙 — 회수된 토큰을 살아 있는 입장 토큰으로 돌려주지 않는다.
        var again = queueService.issue(941L, EVENT);
        assertThat(again.token()).isNotEqualTo(token);
        assertThat(again.status()).isEqualTo("WAITING");
    }

    @Test
    void 입장창이_지났지만_회수_전이면_상태와_게이트가_함께_입장으로_본다() {
        String token = queueService.issue(942L, EVENT).token();
        admissionService.admit(EVENT);
        redisTemplate.opsForZSet().add("queue:admitexp:" + EVENT, token, 1); // 점수는 과거, 회수는 아직

        assertThat(queueService.status(token).status()).isEqualTo("ADMITTED");
        assertThat(queueService.isAdmitted(token, EVENT, 942L)).isTrue();
    }

    @Test
    void 입장창이_지났고_admit키도_없으면_상태와_게이트가_함께_거부한다() {
        String token = queueService.issue(943L, EVENT).token();
        admissionService.admit(EVENT);
        redisTemplate.opsForZSet().add("queue:admitexp:" + EVENT, token, 1);
        redisTemplate.delete("queue:admit:" + token);

        assertThat(queueService.status(token).status()).isEqualTo("EXPIRED");
        assertThat(queueService.isAdmitted(token, EVENT, 943L)).isFalse();
    }
}
