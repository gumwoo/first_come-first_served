package com.flowticket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 대기열 토큰 소유권(Testcontainers Redis): 입장 토큰은 발급받은 회원만 쓴다.
 * - 좌석 게이트(isAdmitted)는 토큰 메타의 userId와 요청 회원을 맞춘다.
 * - 이탈(leave)은 본인 토큰만 — 남의 토큰이면 FORBIDDEN이고 대기·입장 상태는 그대로다.
 * - 승격 때 토큰 메타 수명을 입장창보다 길게 늘려, 오래 기다린 뒤 승격돼도 입장창 안에서 주인 확인이 된다.
 */
@TestPropertySource(properties = {"queue.capacity=3", "queue.admit-ttl=300"})
@SpringBootTest
class QueueTokenOwnershipIntegrationTest extends IntegrationTestSupport {

    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired EventRepository eventRepository;

    private Long EVENT;

    @BeforeEach
    void 판매중_이벤트를_만든다() {
        EVENT = eventRepository.save(Event.builder()
                .kopisId("QUEUE-OWNER").title("소유권").genre("연극").status(EventStatus.ON_SALE).build())
                .getId();
    }

    @Test
    void 입장_토큰은_주인만_좌석_게이트를_통과한다() {
        String token = queueService.issue(1000L, EVENT).token();
        admissionService.admit(EVENT);

        assertThat(queueService.isAdmitted(token, EVENT, 1000L)).isTrue();
        assertThat(queueService.isAdmitted(token, EVENT, 1001L)).isFalse();
        assertThat(queueService.isAdmitted(token, EVENT, null)).isFalse();
    }

    @Test
    void 메타가_없는_토큰은_입장창_안이어도_거부한다() {
        // 메타 없이 승격된 토큰 = 수명이 다한 토큰이 승격된 경우. 주인을 확인할 수 없으니 통과시키지 않는다.
        String token = queueService.issue(1010L, EVENT).token();
        admissionService.admit(EVENT);
        redisTemplate.delete("queue:token:" + token);

        assertThat(queueService.isAdmitted(token, EVENT, 1010L)).isFalse();
    }

    @Test
    void 승격하면_메타_수명이_입장창보다_길어진다() {
        String token = queueService.issue(1020L, EVENT).token();
        // 발급 뒤 오래 기다려 메타 수명이 거의 남지 않은 상태를 만든다.
        redisTemplate.expire("queue:token:" + token, Duration.ofSeconds(5));

        admissionService.admit(EVENT);

        Long ttl = redisTemplate.getExpire("queue:token:" + token);
        assertThat(ttl).isGreaterThan(300L); // 입장창(admit-ttl=300) + 여유
        assertThat(queueService.isAdmitted(token, EVENT, 1020L)).isTrue();
    }

    @Test
    void 승격은_더_긴_메타_수명을_줄이지_않는다() {
        String token = queueService.issue(1030L, EVENT).token(); // 메타 TTL = token-ttl(기본 1,800초)
        admissionService.admit(EVENT);

        assertThat(redisTemplate.getExpire("queue:token:" + token)).isGreaterThan(1000L);
    }

    @Test
    void 남의_토큰으로_이탈하면_FORBIDDEN이고_대기는_그대로다() {
        String token = queueService.issue(1040L, EVENT).token();

        assertThatThrownBy(() -> queueService.leave(token, 1041L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(redisTemplate.opsForZSet().score("queue:wait:" + EVENT, token)).isNotNull();
        assertThat(redisTemplate.hasKey("queue:token:" + token)).isTrue();
        assertThat(redisTemplate.opsForValue().get("queue:user:" + EVENT + ":1040")).isEqualTo(token);
    }

    @Test
    void 남의_입장_토큰으로_이탈하면_FORBIDDEN이고_슬롯은_그대로다() {
        String token = queueService.issue(1050L, EVENT).token();
        admissionService.admit(EVENT);

        assertThatThrownBy(() -> queueService.leave(token, 1051L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        assertThat(redisTemplate.opsForZSet().score("queue:admitexp:" + EVENT, token)).isNotNull();
        assertThat(redisTemplate.opsForValue().get("queue:admitcount:" + EVENT)).isEqualTo("1");
        assertThat(queueService.isAdmitted(token, EVENT, 1050L)).isTrue();
    }
}
