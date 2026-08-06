package com.flowticket.global.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.flowticket.seat.sse.SeatSseRegistry;
import com.flowticket.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SSE 알림은 <b>커밋된 사실만</b> 알려야 한다. 롤백된 트랜잭션이 알림을 내보내면 클라이언트는
 * 일어나지 않은 일을 통보받고, 그 알림으로 재조회한 화면이 오히려 옛 상태를 보여준다.
 *
 * <p>pubSub 없이(=로컬 폴백) 스파이를 세워 Redis에 의존하지 않고 결정적으로 검증한다.
 * 검증 대상은 "언제 나가는가"이지 "어디로 나가는가"가 아니다(팬아웃은 SseFanoutIntegrationTest).
 */
@SpringBootTest
class SseAfterCommitIntegrationTest extends IntegrationTestSupport {

    @Autowired PlatformTransactionManager txManager;

    private SeatSseRegistry localRegistry() {
        return spy(new SeatSseRegistry(1_800_000L, null));
    }

    @Test
    @Timeout(20)
    void 롤백되면_알림이_나가지_않는다() {
        SeatSseRegistry registry = localRegistry();
        AtomicBoolean sentBeforeCommit = new AtomicBoolean(true);

        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(st -> {
            registry.broadcast(7L, "seat.held", Map.of("seatIds", List.of(1L)));
            // 트랜잭션 안에서는 아직 나가면 안 된다 — 이 시점의 상태는 확정되지 않았다.
            sentBeforeCommit.set(hasDelivered(registry));
            throw new IllegalStateException("강제 롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(sentBeforeCommit).as("커밋 전에 이미 나갔다면 미확정 상태를 알린 것이다").isFalse();
        verify(registry, never()).deliverLocal(anyLong(), anyString(), any());
    }

    @Test
    @Timeout(20)
    void 커밋되면_알림이_나간다() {
        SeatSseRegistry registry = localRegistry();

        new TransactionTemplate(txManager).executeWithoutResult(st ->
                registry.broadcast(8L, "seat.held", Map.of("seatIds", List.of(2L))));

        // 미루는 것과 삼키는 것은 다르다 — 커밋된 알림은 반드시 나가야 한다.
        verify(registry).deliverLocal(eq(8L), eq("seat.held"), any());
    }

    @Test
    @Timeout(20)
    void 트랜잭션이_없으면_그_자리에서_나간다() {
        SeatSseRegistry registry = localRegistry();

        // 스케줄러 밖 호출·Kafka 소비처럼 트랜잭션이 없는 경로가 실제로 있다(OrderEventConsumer).
        registry.broadcast(9L, "seat.hold.expired", Map.of("seatIds", List.of(3L)));

        verify(registry).deliverLocal(eq(9L), eq("seat.hold.expired"), any());
    }

    /** 스파이에 전달 호출이 있었는지. verify는 실패 시 예외라 트랜잭션 안에서 쓰기 부적절. */
    private static boolean hasDelivered(SeatSseRegistry registry) {
        return !org.mockito.Mockito.mockingDetails(registry).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("deliverLocal"))
                .toList().isEmpty();
    }
}
