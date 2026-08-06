package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.support.IntegrationTestSupport;
import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.order.service.RefundService;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.dto.HoldResponse;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatHoldExpiryService;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 1인 구매 한도(seat.max-per-user)의 정합성.
 *
 * <p>좌석 자체의 초과판매는 조건부 UPDATE가 막지만, <b>1인 한도는 단일 행에 표현되지 않는
 * 집계 규칙</b>이라 같은 방식으로 지켜지지 않는다. 한도가 세는 대상은 "이 사용자가 이 공연에서
 * 실제로 붙들고 있는 서로 다른 좌석"이며, 점유의 진실원은 두 곳이다:
 *
 * <pre>
 *   결제 전 점유 → SeatHold = HELD
 *   결제 후 점유 → Order    = PAID
 * </pre>
 *
 * <p>주문의 중간 상태(PENDING·VBANK_WAITING)는 <b>세지 않는다</b> — 좌석을 점유 중이라면
 * 이미 HELD 홀드로 잡히고, 홀드가 풀린 뒤 남은 주문은 좌석을 확보하고 있지 않기 때문이다.
 *
 * <p>hold-ttl을 길게 두는 이유: 주문·결제를 거치는 시나리오가 홀드 만료에 걸리지 않아야 한다.
 * 만료가 필요한 테스트는 sweep을 직접 호출한다(스케줄러는 통합테스트에서 비활성).
 */
@TestPropertySource(properties = {"seat.hold-ttl=300"})
@SpringBootTest
class SeatQuotaIntegrationTest extends IntegrationTestSupport {

    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatHoldExpiryService holdExpiryService;
    @Autowired com.flowticket.order.service.OrderExpiryService orderExpiryService;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    @Autowired PaymentService paymentService;
    @Autowired RefundService refundService;

    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;

    private Long eventId;

    @BeforeEach
    void seed() {
        Event e = eventRepository.save(Event.builder()
                .kopisId("QUOTA1").title("한도 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    // ------------------------------------------------------------------
    // ① 동시성 — check-then-act 레이스
    // ------------------------------------------------------------------

    @Test
    void 한도직전에_동시요청이_오면_하나만_성공한다() throws Exception {
        // 3매 보유 상태에서 서로 다른 1매를 동시에 요청하면, 한 쪽만 성공해야 한다.
        // 한도 검사가 읽기→검사→행위로 나뉘어 있으면 둘 다 3+1=4를 통과해 5매가 된다.
        long user = 501L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(5);
        seatService.hold(user, eventId, ids.subList(0, 3), token);

        List<Long> a = List.of(ids.get(3));
        List<Long> b = List.of(ids.get(4));
        AtomicInteger success = concurrent(2, i -> {
            seatService.hold(user, eventId, i == 0 ? a : b, token);
            return 1;
        });

        assertThat(success.get()).isEqualTo(1);
        assertThat(heldSeatCount(user)).isEqualTo(4);
    }

    @Test
    void 결제전환과_추가선점이_겹쳐도_한도를_넘지_않는다() throws Exception {
        // 결제는 좌석 수를 늘리지 않고 표현 위치만 옮긴다(HELD → PAID).
        // 한도 조회가 두 문장으로 나뉘면 그 사이에 전환이 커밋돼 0매로 보일 수 있다(읽기 스큐).
        long user = 502L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(5);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        Long orderId = orderService.create(user, held.holdId()).orderId();

        AtomicInteger success = concurrent(2, i -> {
            if (i == 0) {
                paymentService.pay(user, orderId, "card", null, "OK-" + orderId);
            } else {
                seatService.hold(user, eventId, List.of(ids.get(4)), token); // 추가 1매 → 실패해야 함
            }
            return i == 0 ? 0 : 1; // 추가 선점이 성공한 경우만 센다
        });

        assertThat(success.get()).isZero();
        assertThat(activeSeatCount(user)).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // ② 무엇을 세는가 — PAID / HELD
    // ------------------------------------------------------------------

    @Test
    void 결제완료_4매_뒤에는_더_선점할_수_없다() {
        // 현재 구현은 HELD 홀드만 센다. 결제하면 홀드가 CONVERTED가 되어 한도에서 빠지므로
        // "결제 → 또 4매 → 결제"를 무한 반복할 수 있다. 동시성 없이도 재현된다.
        long user = 503L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(5);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        Long orderId = orderService.create(user, held.holdId()).orderId();
        paymentService.pay(user, orderId, "card", null, "OK-" + orderId);

        assertThatThrownBy(() -> seatService.hold(user, eventId, List.of(ids.get(4)), token))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 가상계좌_입금대기중에는_더_선점할_수_없다() {
        // VBANK_WAITING 주문의 좌석은 결제 확정 전까지 홀드가 HELD로 남아 있으므로
        // 주문 상태를 세지 않아도 한도에 잡혀야 한다.
        long user = 504L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(5);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        Long orderId = orderService.create(user, held.holdId()).orderId();
        paymentService.pay(user, orderId, "vbank", null, "VB-" + orderId);

        assertThatThrownBy(() -> seatService.hold(user, eventId, List.of(ids.get(4)), token))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 주문과_홀드가_같은_좌석을_가리켜도_이중으로_세지_않는다() {
        // 주문 생성 후에는 같은 좌석이 order_items 와 seat_hold_items 양쪽에 존재한다.
        // 단순 덧셈으로 세면 4매가 8매가 되어 정상 사용자를 막는다.
        long user = 505L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(4);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 3), token);
        orderService.create(user, held.holdId()); // PENDING — 같은 좌석 3매가 양쪽에 존재

        // 3매만 붙들고 있으므로 1매는 더 잡을 수 있어야 한다(6매로 세면 여기서 거부된다).
        seatService.hold(user, eventId, List.of(ids.get(3)), token);

        assertThat(activeSeatCount(user)).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // ③ 한도가 풀려야 하는 경우 — 과잉 차단 방지
    // ------------------------------------------------------------------

    @Test
    void 환불하면_한도가_복구된다() {
        // 환불은 좌석을 재고로 돌려준다(SOLD → AVAILABLE). 재고는 돌려주면서 한도만 묶어두면
        // 그 사용자는 이 공연에서 영영 살 수 없게 된다.
        long user = 506L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(8);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        Long orderId = orderService.create(user, held.holdId()).orderId();
        paymentService.pay(user, orderId, "card", null, "OK-" + orderId);
        refundService.refund(user, orderId, "변심", "R-" + orderId);

        seatService.hold(user, eventId, ids.subList(4, 8), token); // 다시 4매

        assertThat(activeSeatCount(user)).isEqualTo(4);
    }

    @Test
    void 주문을_남긴채_홀드를_해제하면_한도가_복구된다() {
        // release()는 홀드만 RELEASED로 바꾸고 주문(PENDING)은 그대로 둔다.
        // 좌석은 이미 반납했으므로 한도도 함께 풀려야 한다.
        long user = 507L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(8);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        orderService.create(user, held.holdId()); // Order = PENDING
        seatService.release(held.holdId(), user); // Hold = RELEASED, Seat = AVAILABLE

        seatService.hold(user, eventId, ids.subList(4, 8), token); // 새로 4매

        assertThat(activeSeatCount(user)).isEqualTo(4);
    }

    @Test
    void 가상계좌_주문이_남아도_홀드가_만료되면_한도가_복구된다() {
        // 홀드가 EXPIRED면 convertHold(HELD 조건부)가 0행이라 그 주문은 결제될 수 없다.
        // 좌석을 확보하지 못하는 주문이므로 한도를 잡아서는 안 된다.
        long user = 508L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(8);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        Long orderId = orderService.create(user, held.holdId()).orderId();
        paymentService.pay(user, orderId, "vbank", null, "VB-" + orderId);

        expireHold(held.holdId()); // 홀드만 만료(주문은 VBANK_WAITING으로 남는다)

        seatService.hold(user, eventId, ids.subList(4, 8), token);

        assertThat(activeSeatCount(user)).isEqualTo(4);
    }

    @Test
    void 주문이_먼저_만료돼도_홀드가_살아있으면_한도가_유지된다() {
        // 주문 만료와 홀드 만료는 서로 다른 스케줄러가 처리해 시간차가 있다.
        // 주문만 EXPIRED인 구간에서도 좌석은 여전히 HELD이므로 한도는 유지돼야 한다.
        long user = 509L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(5);
        HoldResponse held = seatService.hold(user, eventId, ids.subList(0, 4), token);
        Long orderId = orderService.create(user, held.holdId()).orderId();
        expireOrder(orderId); // 주문만 EXPIRED, 홀드는 HELD 유지

        assertThatThrownBy(() -> seatService.hold(user, eventId, List.of(ids.get(4)), token))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    /** 이 사용자가 이 공연에서 실제로 붙들고 있는 서로 다른 좌석 수(PAID 주문 ∪ HELD 홀드). */
    private long activeSeatCount(long userId) {
        return seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.HELD || s.getStatus() == SeatStatus.SOLD)
                .filter(s -> ownedBy(s.getId(), userId))
                .count();
    }

    private long heldSeatCount(long userId) {
        return seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.HELD)
                .filter(s -> ownedBy(s.getId(), userId))
                .count();
    }

    private boolean ownedBy(Long seatId, long userId) {
        return holdRepository.findAll().stream()
                .filter(h -> h.getUserId().equals(userId) && h.getEventId().equals(eventId))
                .anyMatch(h -> holdItemRepository.findByHoldId(h.getId()).stream()
                        .anyMatch(i -> i.getSeatId().equals(seatId)));
    }

    private List<Long> availableSeatIds(int n) {
        return seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(n).toList();
    }

    private String admittedToken(long userId) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        return token;
    }

    private AtomicInteger concurrent(int threads, IndexedOp op) throws Exception {
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (op.run(idx) == 1) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 한도 초과·락 대기 등으로 실패한 쪽은 세지 않는다
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        return success;
    }

    interface IndexedOp {
        int run(int index) throws Exception;
    }

    /**
     * 홀드만 만료시킨다(주문은 그대로). 만료 시각을 과거로 당긴 뒤 <b>실제 sweep</b>을 돌린다 —
     * 상태만 손으로 바꾸면 좌석이 HELD로 남아 현실과 달라진다(sweep은 좌석도 복구한다).
     */
    private void expireHold(Long holdId) {
        jdbc.update("update seat_holds set expires_at = now() - interval '1 minute' where id = ?", holdId);
        holdExpiryService.sweepExpired();
    }

    /** 주문만 만료시킨다(홀드는 그대로). 주문·홀드 만료가 별도 스케줄러라는 사실을 그대로 재현. */
    private void expireOrder(Long orderId) {
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?", orderId);
        orderExpiryService.sweepExpired();
    }
}
