package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OutboxEvent}의 상태 전이 계약. 컨테이너 없이 도는 단위 테스트다 — 검증 대상이
 * 발행 파이프라인이 아니라 <b>엔티티가 남기는 흔적</b>이기 때문이다.
 *
 * <p>{@code lastError}의 수명이 요점이다. 일시적 실패에도 기록되므로, 지우지 않으면
 * "PUBLISHED인데 오류가 붙어 있는" 상태가 남아 운영자가 미해결로 오해한다.
 */
class OutboxEventTest {

    @Test
    @DisplayName("일시적 실패 후 발행에 성공하면 실패 원인은 지우고 시도 횟수는 남긴다")
    void 발행_성공시_마지막_실패원인을_지운다() {
        OutboxEvent event = newEvent();

        event.markAttemptFailed("TimeoutException: broker unreachable");
        event.markAttemptFailed("TimeoutException: broker unreachable");
        assertThat(event.getLastError()).isNotBlank();

        event.markPublished();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getLastError())
                .as("PUBLISHED에 오류가 남아 있으면 운영자가 미해결로 오해한다")
                .isNull();
        assertThat(event.getAttempts())
                .as("몇 번 만에 나갔는지는 운영 가시성이라 지우지 않는다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("결정적 실패는 격리 근거를 남긴 채 DEAD가 된다")
    void 결정적_실패는_근거를_남기고_격리된다() {
        OutboxEvent event = newEvent();

        event.markDead("JsonParseException: Unexpected end-of-input");

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getLastError())
                .as("격리 근거가 없으면 운영자가 폐기/복구를 판단할 수 없다")
                .contains("JsonParseException");
        assertThat(event.getAttempts())
                .as("\"0회 시도인데 DEAD\"로 보이면 원인을 오해한다 — 한 번 시도했고 결과가 결정적이었다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("실패 원인이 길어도 잘라 보관한다")
    void 긴_실패원인은_잘린다() {
        OutboxEvent event = newEvent();

        event.markAttemptFailed("x".repeat(2_000));

        assertThat(event.getLastError())
                .as("스택트레이스가 통째로 들어와 로그·조회를 뭉개지 않게 자른다")
                .hasSize(500);
    }

    private static OutboxEvent newEvent() {
        return new OutboxEvent(UUID.randomUUID(), "order", 1L, "order.paid", "{}");
    }
}
