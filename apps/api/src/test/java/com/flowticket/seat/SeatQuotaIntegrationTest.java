package com.flowticket.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowticket.support.IntegrationTestSupport;
import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.order.service.RefundService;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.dto.HoldResponse;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatQuotaRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatHoldExpiryService;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    @Autowired SeatQuotaRepository quotaRepository;
    @Autowired PlatformTransactionManager txManager;

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
        //
        // ⚠️ 성격: **결함 탐지용이지 회귀 가드가 아니다.** 시작 래치는 두 스레드를 같이
        // 출발시킬 뿐이라, T1이 커밋까지 끝낸 뒤 T2가 읽으면 결함이 있어도 통과한다.
        // 실제로 이 테스트는 수정 전 CI에서 결함을 잡았지만, 앞으로도 잡는다는 보장은 없다.
        // 회귀는 아래 `직렬화_락이_같은_사용자의_동시_선점을_대기시킨다`가 결정적으로 지킨다.
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
        //
        // ⚠️ 이 테스트는 결함 탐지용이지 회귀 가드가 아니다 — 아래 §타이밍 참고.
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

        // 추가 선점은 실패해야 한다.
        assertThat(success.get()).isZero();

        // ⚠️ "추가 선점 0건"만으로는 부족하다. 결제 스레드가 조용히 실패해도 기존 4매가 HELD로
        // 남아 좌석 수는 그대로 4가 되어 **거짓 통과**한다. 결제가 실제로 확정됐는지 확인한다.
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .as("결제가 실제로 확정돼야 이 테스트가 의미를 갖는다")
                .isEqualTo(OrderStatus.PAID);
        assertThat(holdRepository.findById(held.holdId()).orElseThrow().getStatus())
                .isEqualTo(SeatHoldStatus.CONVERTED);
        assertThat(statusOf(ids.subList(0, 4))).containsOnly(SeatStatus.SOLD);
        assertThat(statusOf(List.of(ids.get(4)))).containsOnly(SeatStatus.AVAILABLE);

        assertThat(activeSeatCount(user)).isEqualTo(4);
    }

    @Test
    void 직렬화_락이_같은_사용자의_동시_선점을_대기시킨다() throws Exception {
        // 레이스 테스트는 타이밍에 의존해 회귀를 보장하지 못한다. 대신 **락 자체의 동작**을
        // 결정적으로 검증한다: 다른 트랜잭션이 같은 (사용자, 공연) 키를 쥐고 있는 동안
        // hold()가 진행되지 못하고, 그 트랜잭션이 끝나야 비로소 한도를 평가한다.
        long user = 510L;
        String token = admittedToken(user);
        List<Long> ids = availableSeatIds(4);
        seatService.hold(user, eventId, ids.subList(0, 3), token); // 3매 보유

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicBoolean holdFinished = new AtomicBoolean(false);
        // 별도 스레드의 예외는 그대로 두면 밖으로 나오지 않는다. 단언은 실패하더라도
        // "왜"가 보이지 않아 진단이 어려우므로 붙잡아 두었다가 함께 확인한다.
        AtomicReference<Throwable> lockerFailure = new AtomicReference<>();
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();

        // A: 트랜잭션을 연 채 같은 키의 advisory lock을 쥐고 대기
        Thread locker = new Thread(() -> {
            try {
                new TransactionTemplate(txManager).executeWithoutResult(st -> {
                    quotaRepository.acquireQuotaLock(Math.toIntExact(user), Math.toIntExact(eventId));
                    lockHeld.countDown();
                    try {
                        releaseLock.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                lockerFailure.set(t);
                lockHeld.countDown(); // 실패해도 대기 쪽이 타임아웃까지 멈춰 있지 않게 한다
            }
        });
        locker.start();
        assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(lockerFailure.get()).as("락을 쥐는 트랜잭션이 실패하면 이 테스트는 무의미하다").isNull();

        // B: 같은 사용자의 추가 선점 — A가 락을 놓을 때까지 진행되면 안 된다
        CountDownLatch holderStarted = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            holderStarted.countDown();
            try {
                seatService.hold(user, eventId, List.of(ids.get(3)), token);
            } catch (Throwable t) {
                holderFailure.set(t);
            } finally {
                holdFinished.set(true);
            }
        });
        holder.start();
        // 스레드가 아직 출발조차 안 한 상태를 "대기 중"으로 오인하지 않도록 확인한다.
        assertThat(holderStarted.await(10, TimeUnit.SECONDS)).isTrue();

        holder.join(1000);
        assertThat(holdFinished).as("락을 쥔 트랜잭션이 살아 있는 동안에는 진행되면 안 된다").isFalse();

        releaseLock.countDown();
        locker.join(10_000);
        holder.join(10_000);
        assertThat(holdFinished).isTrue();
        assertThat(lockerFailure.get()).isNull();
        assertThat(holderFailure.get()).as("락 해제 후에는 정상적으로 선점돼야 한다").isNull();

        // 락을 기다린 뒤 한도를 평가했으므로 3+1=4로 성공한다.
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

    private List<SeatStatus> statusOf(List<Long> seatIds) {
        return seatRepository.findAllById(seatIds).stream().map(Seat::getStatus).toList();
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

    /**
     * 동시 실행 헬퍼.
     *
     * <p><b>예상 밖 예외를 삼키지 않는다.</b> 기대하는 실패는 오직
     * {@link ErrorCode#MAX_PER_USER_EXCEEDED} 하나이고, 그 외는 전부 모아서 테스트를 실패시킨다.
     * 모든 예외를 무시하면 쿼리가 깨져 양쪽 다 오류로 끝나도 "성공 0건"이 되어
     * <b>거짓 통과</b>가 난다.
     *
     * <p>{@code BusinessException} 전체를 삼키는 것도 넓다 — {@code SOLD_OUT}이나
     * {@code QUEUE_NOT_ADMITTED}로 실패해도 통과해 버린다. "실패했다"가 아니라
     * <b>"이 이유로 실패했다"</b>를 단언해야 나중에 실패 사유가 바뀌었을 때 드러난다.
     */
    private AtomicInteger concurrent(int threads, IndexedOp op) throws Exception {
        AtomicInteger success = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
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
                } catch (BusinessException e) {
                    if (e.getErrorCode() != ErrorCode.MAX_PER_USER_EXCEEDED) {
                        unexpected.add(e); // 다른 사유의 실패는 이 테스트가 기대한 것이 아니다
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        assertThat(unexpected).as("예상 밖 예외가 발생하면 거짓 통과가 된다").isEmpty();
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
