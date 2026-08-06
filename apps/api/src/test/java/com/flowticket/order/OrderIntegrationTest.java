package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.test.context.TestPropertySource;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.dto.OrderResponse;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 주문 생성(BE-1): hold→order 승격 + 가격 스냅샷 + 멱등 + 소유자/만료 검증.
 * capacity 높게·워커 비활성으로 결정적, hold-ttl 1s로 만료 케이스 재현.
 */
@TestPropertySource(properties = {"seat.hold-ttl=1"})
@SpringBootTest
class OrderIntegrationTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;

    private Long eventId;

    @BeforeEach
    void seed() {
        // 테스트 격리 — FK 순서대로 정리(order_items→orders→hold_items→holds→seats→prices→events)
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();

        Event e = eventRepository.save(Event.builder()
                .kopisId("ORD1").title("주문 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 주문_생성은_hold를_승격하고_가격을_스냅샷한다() {
        long user = 10L;
        Long holdId = holdSeats(user, 2);

        OrderResponse res = orderService.create(user, holdId);

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.items()).hasSize(2);
        assertThat(res.amount()).isPositive();
        assertThat(res.items().stream().mapToInt(OrderResponse.OrderItemResponse::price).sum())
                .isEqualTo(res.amount()); // amount = 라인 가격 합(스냅샷)
    }

    @Test
    void 같은_hold로_재생성하면_동일_주문을_반환한다() {
        long user = 11L;
        Long holdId = holdSeats(user, 1);

        Long o1 = orderService.create(user, holdId).orderId();
        Long o2 = orderService.create(user, holdId).orderId();

        assertThat(o2).isEqualTo(o1); // 멱등(더블 POST 방어)
    }

    @Test
    void 같은_hold로_동시에_생성해도_주문은_하나만_남는다() throws Exception {
        // 순차 멱등은 "찾고 → 없으면 만든다"로 충분하지만, 동시에 오면 둘 다 "없음"을 보고
        // 각자 INSERT한다. 같은 좌석에 활성 주문이 둘 생기면 둘 다 결제 시도가 가능해지고,
        // 두 번째는 좌석 조건부 UPDATE(HELD→SOLD)에서 0행으로 롤백되지만 그 전에 PG 승인이
        // 나갔다면 미아 승인이 남는다(ADR-011 정산 대상). 앱 검사만으로는 못 막고
        // DB 제약이 최종 방어선이어야 한다.
        long user = 14L;
        Long holdId = holdSeats(user, 1);

        List<Long> created = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    created.add(orderService.create(user, holdId).orderId());
                } catch (BusinessException expected) {
                    // 이 경로에서 나올 수 있는 도메인 예외는 실패로 보지 않는다
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).as("예상 밖 예외가 나면 거짓 통과가 된다").isEmpty();
        // 모든 호출이 같은 주문을 봐야 하고, DB에도 이 hold의 활성 주문은 하나뿐이어야 한다.
        assertThat(created).isNotEmpty();
        assertThat(Set.copyOf(created)).as("서로 다른 주문이 만들어지면 안 된다").hasSize(1);
        assertThat(orderRepository.findAll().stream()
                .filter(o -> o.getHoldId().equals(holdId))
                .filter(o -> o.getStatus() == OrderStatus.PENDING
                        || o.getStatus() == OrderStatus.VBANK_WAITING)
                .count()).isEqualTo(1);
    }

    @Test
    void 남의_hold로_주문을_만들_수_없다() {
        Long holdId = holdSeats(12L, 1);

        assertThatThrownBy(() -> orderService.create(999L, holdId))
                .isInstanceOf(BusinessException.class); // FORBIDDEN
    }

    @Test
    void 만료된_hold로는_주문을_만들_수_없다() throws Exception {
        long user = 13L;
        Long holdId = holdSeats(user, 1);

        Thread.sleep(1500); // hold-ttl(1s) 경과

        assertThatThrownBy(() -> orderService.create(user, holdId))
                .isInstanceOf(BusinessException.class); // HOLD_EXPIRED
    }

    // --- helpers ---

    private Long holdSeats(long userId, int count) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(count).toList();
        return seatService.hold(userId, eventId, ids, token).holdId();
    }
}
