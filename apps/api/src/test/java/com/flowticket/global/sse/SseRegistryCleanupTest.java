package com.flowticket.global.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.sse.SeatSseRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 구독자가 없어진 키가 레지스트리에 남지 않는지 검증한다.
 *
 * 키는 공연·주문 ID다. /sse/** 는 공개라 임의의 공연 ID로도 구독할 수 있고, 주문 ID는 계속 늘어난다.
 * 끊긴 뒤에도 빈 Set이 남으면 맵이 접속이 지나간 ID 수만큼 커진다.
 *
 * 끝난 emitter에 보내면 send가 실패한다. 전달 실패 경로로 제거를 태운다.
 */
class SseRegistryCleanupTest {

    private final SeatSseRegistry seats = new SeatSseRegistry(1_800_000L, null);
    private final OrderSseRegistry orders = new OrderSseRegistry(1_800_000L, null);

    @SuppressWarnings("unchecked")
    private Map<Long, Set<SseEmitter>> seatKeys() {
        return (Map<Long, Set<SseEmitter>>) ReflectionTestUtils.getField(seats, "byEvent");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Set<SseEmitter>> orderKeys() {
        return (Map<Long, Set<SseEmitter>>) ReflectionTestUtils.getField(orders, "byOrder");
    }

    @Test
    void 좌석_구독자가_모두_사라지면_키도_지운다() {
        for (long id = 1; id <= 100; id++) {
            SseEmitter emitter = seats.subscribe(id);
            emitter.complete();
            seats.deliverLocal(id, "seat.held", Map.of());
        }

        assertThat(seatKeys()).isEmpty();
    }

    @Test
    void 주문_구독자가_모두_사라지면_키도_지운다() {
        for (long id = 1; id <= 100; id++) {
            SseEmitter emitter = orders.subscribe(id);
            emitter.complete();
            orders.deliverLocal(id, "order.paid", Map.of());
        }

        assertThat(orderKeys()).isEmpty();
    }

    @Test
    void 구독자가_남아_있으면_키를_지우지_않는다() {
        SseEmitter gone = seats.subscribe(1L);
        SseEmitter alive = seats.subscribe(1L);
        gone.complete();

        seats.deliverLocal(1L, "seat.held", Map.of());

        assertThat(seatKeys().get(1L)).containsExactly(alive);
    }

    /**
     * 마지막 구독자 제거와 새 구독이 겹치는 순서. 새 구독자가 맵에서 떨어져 나간 Set에 들어가면
     * 연결은 살아 있는데 이벤트를 영영 받지 못한다.
     */
    @Test
    void 제거와_새_구독이_겹쳐도_새_구독자는_맵에_남는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2_000; i++) {
                long id = i;
                SseEmitter gone = seats.subscribe(id);
                gone.complete();

                Future<?> removal = pool.submit(() -> seats.deliverLocal(id, "seat.held", Map.of()));
                Future<SseEmitter> joining = pool.submit(() -> seats.subscribe(id));
                removal.get(5, TimeUnit.SECONDS);
                SseEmitter joined = joining.get(5, TimeUnit.SECONDS);

                assertThat(seatKeys().get(id)).contains(joined);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
