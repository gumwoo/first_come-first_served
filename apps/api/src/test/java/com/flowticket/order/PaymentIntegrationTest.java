package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.queue.service.QueueAdmissionService;
import com.flowticket.queue.service.QueueService;
import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatHoldStatus;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.EventSeatPriceRepository;
import com.flowticket.seat.repository.SeatHoldItemRepository;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import com.flowticket.seat.service.SeatSeeder;
import com.flowticket.seat.service.SeatService;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 승인: Mock 승인 → PENDING→PAID(조건부) + 좌석 SOLD + hold CONVERTED,
 * 거절 시 payment FAILED/order PENDING 유지, 멱등, 이미 PAID 재결제 거부.
 */
@SpringBootTest
class PaymentIntegrationTest extends IntegrationTestSupport {

    @Autowired OrderService orderService;
    @Autowired PaymentService paymentService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;
    @SpyBean com.flowticket.order.gateway.PaymentGateway gateway;
    @Autowired com.flowticket.outbox.repository.OutboxEventRepository outboxRepository;

    private Long eventId;

    @BeforeEach
    void seed() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();

        Event e = eventRepository.save(Event.builder()
                .kopisId("PAY1").title("결제 테스트").genre("연극").status(EventStatus.ON_SALE).build());
        eventId = e.getId();
        seatSeeder.seedForEvent(eventId);
    }

    @Test
    void 승인_성공은_주문PAID_좌석SOLD_홀드CONVERTED() {
        long user = 20L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.pay(user, c.orderId, "card", null, "OK-" + c.orderId);

        assertThat(res.paymentStatus()).isEqualTo("APPROVED");
        assertThat(res.orderStatus()).isEqualTo("PAID");
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThat(holdRepository.findById(c.holdId).orElseThrow().getStatus()).isEqualTo(SeatHoldStatus.CONVERTED);
    }

    @Test
    void 승인_거절은_결제FAILED_주문은_PENDING_유지() {
        long user = 21L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.pay(user, c.orderId, "card", null, "FAIL-" + c.orderId);

        assertThat(res.paymentStatus()).isEqualTo("FAILED");
        assertThat(res.orderStatus()).isEqualTo("PENDING"); // 재시도 가능
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void 같은_멱등키_재결제는_한번만_처리된다() {
        long user = 22L;
        Ctx c = order(user, 1);
        String key = "OK-idem-" + c.orderId;

        Long p1 = paymentService.pay(user, c.orderId, "card", null, key).paymentId();
        Long p2 = paymentService.pay(user, c.orderId, "card", null, key).paymentId();

        assertThat(p2).isEqualTo(p1); // 같은 결제 재사용
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void 이미_PAID_주문은_재결제할_수_없다() {
        long user = 23L;
        Ctx c = order(user, 1);
        paymentService.pay(user, c.orderId, "card", null, "OK-" + c.orderId);

        assertThatThrownBy(() -> paymentService.pay(user, c.orderId, "card", null, "OK2-" + c.orderId))
                .isInstanceOf(BusinessException.class); // INVALID_STATE_TRANSITION
    }

    @Test
    void 결제창_확정은_주문PAID_좌석SOLD() {
        long user = 24L;
        Ctx c = order(user, 1);

        PaymentResponse res = paymentService.confirm(user, c.orderId, "OK-KEY-" + c.orderId);

        assertThat(res.paymentStatus()).isEqualTo("APPROVED");
        assertThat(res.orderStatus()).isEqualTo("PAID");
        assertThat(seatRepository.findById(c.seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void 결제창_확정_같은_paymentKey는_한번만_처리된다() {
        long user = 25L;
        Ctx c = order(user, 1);
        String key = "OK-KEY-idem-" + c.orderId;

        Long p1 = paymentService.confirm(user, c.orderId, key).paymentId();
        Long p2 = paymentService.confirm(user, c.orderId, key).paymentId();

        assertThat(p2).isEqualTo(p1);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    /**
     * PG 응답을 기다리는 동안 주문 만료 sweep이 먼저 돈 경우. 좌석 만료가 먼저인 경우(아래)와 달리 markPaid가
     * 0행이다. 이 분기가 정상 종료하면 주문은 EXPIRED인데 승인이 PG에 그대로 남는다.
     */
    @Test
    void PG_응답_대기_중_주문이_만료되면_승인을_취소하고_결제는_실패로_남는다() {
        long user = 62L;
        Ctx c = order(user, 1);
        String key = "OK-" + c.orderId();
        changeOrderStatusDuringApproval(c.orderId(), "EXPIRED");

        assertThatThrownBy(() -> paymentService.pay(user, c.orderId(), "card", null, key))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATE_TRANSITION);

        verify(gateway, times(1)).refund(eq("MOCK-" + key), anyInt(), eq("void-MOCK-" + key));
        assertThat(orderStat(c.orderId())).isEqualTo("EXPIRED");
        assertThat(paymentStat(c.orderId())).isEqualTo("FAILED");
    }

    /** 다른 멱등키의 결제가 먼저 확정한 경우. 주문이 PAID라 정산 후보도 아니어서, 여기서 취소하지 않으면 아무도 안 한다. */
    @Test
    void PG_응답_대기_중_다른_결제가_주문을_확정하면_늦은_승인을_취소한다() {
        long user = 63L;
        Ctx c = order(user, 1);
        String key = "OK-" + c.orderId();
        changeOrderStatusDuringApproval(c.orderId(), "PAID");

        assertThatThrownBy(() -> paymentService.pay(user, c.orderId(), "card", null, key))
                .isInstanceOf(BusinessException.class);

        verify(gateway, times(1)).refund(eq("MOCK-" + key), anyInt(), eq("void-MOCK-" + key));
        assertThat(orderStat(c.orderId())).isEqualTo("PAID");
        assertThat(paymentStat(c.orderId())).isEqualTo("FAILED");
    }

    /** 보상 취소에 실패하면 승인 번호가 DB에 남아야 한다. 주문이 PAID면 정산도 보지 않아 이것이 유일한 단서다. */
    @Test
    void 보상_취소에_실패해도_결제_행에_승인_번호가_남는다() {
        long user = 70L;
        Ctx c = order(user, 1);
        String key = "OK-" + c.orderId();
        changeOrderStatusDuringApproval(c.orderId(), "PAID");
        doReturn(ApproveResult.unknown("취소 결과 불명")).when(gateway).refund(anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> paymentService.pay(user, c.orderId(), "card", null, key))
                .isInstanceOf(BusinessException.class);

        assertThat(paymentStat(c.orderId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select pg_tid from payments where order_id=?", String.class, c.orderId()))
                .isEqualTo("MOCK-" + key);
    }

    /** 승인이 거절된 결제에는 승인 번호가 없다. 재시도 가능한 실패와 취소 대상을 구분하는 기준이다. */
    @Test
    void 승인_거절된_결제에는_승인_번호가_없다() {
        long user = 71L;
        Ctx c = order(user, 1);

        paymentService.pay(user, c.orderId(), "card", null, "FAIL-" + c.orderId());

        assertThat(jdbc.queryForObject("select pg_tid from payments where order_id=?", String.class, c.orderId()))
                .isNull();
    }

    /** 가상계좌 발급을 기다리는 사이 주문이 끝난 경우. 계좌를 내주면 입금을 받을 주문이 없다. */
    @Test
    void 가상계좌_발급_중_주문이_만료되면_계좌를_내주지_않는다() {
        long user = 72L;
        Ctx c = order(user, 1);
        doAnswer(inv -> {
            jdbc.update("update orders set status='EXPIRED' where id=?", c.orderId());
            return inv.callRealMethod();
        }).when(gateway).issueVbank(eq(c.orderId()), anyInt());

        assertThatThrownBy(() -> paymentService.pay(user, c.orderId(), "vbank", null, "VB-" + c.orderId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_STATE_TRANSITION);

        assertThat(orderStat(c.orderId())).isEqualTo("EXPIRED");
        assertThat(paymentStat(c.orderId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select vbank_account from payments where order_id=?",
                String.class, c.orderId())).isNull();
    }

    /** 같은 입금이 동시에 두 번 들어온 경우. 먼저 끝난 쪽이 이미 확정했으니 뒤쪽은 조용히 끝나야 한다. */
    @Test
    void 같은_입금이_동시에_들어오면_뒤쪽은_취소_없이_끝난다() throws Exception {
        long user = 64L;
        Ctx c = order(user, 1);
        paymentService.pay(user, c.orderId(), "vbank", null, "VB-" + c.orderId());

        depositWhileOrderBecomes(user, c.orderId(), "PAID").get(10, TimeUnit.SECONDS);

        verify(gateway, never()).refund(anyString(), anyInt(), anyString());
        assertThat(orderStat(c.orderId())).isEqualTo("PAID");
    }

    /** 입금이 주문 만료와 겹친 경우. 같은 0행이어도 이쪽은 확정된 적이 없으므로 롤백한다. */
    @Test
    void 입금_처리_중_주문이_만료되면_확정하지_않고_롤백한다() throws Exception {
        long user = 65L;
        Ctx c = order(user, 1);
        paymentService.pay(user, c.orderId(), "vbank", null, "VB-" + c.orderId());

        Future<?> deposit = depositWhileOrderBecomes(user, c.orderId(), "EXPIRED");

        assertThatThrownBy(() -> deposit.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(BusinessException.class);
        assertThat(orderStat(c.orderId())).isEqualTo("EXPIRED");
        assertThat(paymentStat(c.orderId())).isEqualTo("READY");
    }

    /** 멱등키 UNIQUE는 전역이다. 다른 주문의 결제를 그대로 돌려주면 결제하지 않은 주문이 승인으로 보인다. */
    @Test
    void 다른_주문의_멱등키를_재사용하면_PG를_부르기_전에_거절한다() {
        Ctx a = order(66L, 1);
        Ctx b = order(67L, 1);
        String key = "OK-SHARED-" + a.orderId();
        paymentService.pay(66L, a.orderId(), "card", null, key);

        assertThatThrownBy(() -> paymentService.pay(67L, b.orderId(), "card", null, key))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);

        verify(gateway, never()).approve(eq(b.orderId()), anyInt(), anyString(), any(), anyString());
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(orderStat(b.orderId())).isEqualTo("PENDING");
    }

    /** 순차 재사용과 다른 경로다. 앞 요청이 커밋되기 전이면 존재 확인을 지나 UNIQUE 충돌로 온다. */
    @Test
    void 다른_주문과_같은_멱등키가_동시에_충돌해도_거절한다() throws Exception {
        Ctx a = order(68L, 1);
        Ctx b = order(69L, 1);
        String key = "OK-SHARED-" + a.orderId();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("insert into payments (order_id, method, amount, idempotency_key) "
                    + "values (" + a.orderId() + ", 'card', 1000, '" + key + "')");

            Future<PaymentResponse> loser = pool.submit(
                    () -> paymentService.pay(69L, b.orderId(), "card", null, key));
            awaitBlockedOn("insert into payments");
            lock.commit();

            assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);
        } finally {
            pool.shutdownNow();
        }
        verify(gateway, never()).approve(eq(b.orderId()), anyInt(), anyString(), any(), anyString());
    }

    // --- helpers ---

    /** 승인 호출이 PG에서 돌아오기 전에 주문 상태가 바뀌는 순서를 고정한다. pay()는 이 구간에 트랜잭션이 없어 바로 커밋된다. */
    private void changeOrderStatusDuringApproval(Long orderId, String status) {
        doAnswer(inv -> {
            jdbc.update("update orders set status=? where id=?", status, orderId);
            return inv.callRealMethod();
        }).when(gateway).approve(eq(orderId), anyInt(), anyString(), any(), anyString());
    }

    /**
     * 입금 처리가 주문을 읽은 뒤 결제 행 쓰기에서 막힌 사이 주문 상태가 바뀌는 순서를 고정한다.
     * 결제 행을 잠가 두고, 입금 처리가 그 잠금을 기다리는 것을 확인한 뒤 주문을 바꾸고 풀어 준다.
     */
    private Future<?> depositWhileOrderBecomes(long user, Long orderId, String status) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("select id from payments where order_id=" + orderId + " for update");

            Future<?> deposit = pool.submit(() -> paymentService.confirmVbankDeposit(user, orderId));
            awaitBlockedOn("update payments");
            jdbc.update("update orders set status=? where id=?", status, orderId);
            lock.commit();
            return deposit;
        } finally {
            pool.shutdown();
        }
    }

    /** 다른 세션이 이 문장에서 행 잠금을 기다릴 때까지 기다린다. */
    private void awaitBlockedOn(String statement) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long blocked = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where wait_event_type = 'Lock' and query ilike ?", Long.class, "%" + statement + "%");
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("잠금 대기가 관측되지 않았다: " + statement);
    }

    private String paymentStat(Long orderId) {
        return jdbc.queryForObject("select status from payments where order_id=?", String.class, orderId);
    }

    @Test
    void 만료sweep이_먼저_이기면_결제는_실패하고_주문은_PAID로_남지_않는다() {
        // TS-011 반대 방향: 만료 sweep이 먼저 승리(좌석 AVAILABLE·홀드 EXPIRED)한 상황을 재현.
        // order는 아직 PENDING·expiresAt 미래(결제 진입 검사 통과)라, 결제가 markPaid엔 성공할 수 있다.
        Ctx c = order(60L, 1);
        jdbc.update("update seats set status='AVAILABLE' where id=?", c.seatId());
        jdbc.update("update seat_holds set status='EXPIRED' where id=?", c.holdId());

        // finalizePaid: markPaid는 성공하나 sellSeats/convertHold가 0행 → 영향행수 검증 실패 → 트랜잭션 롤백
        assertThatThrownBy(() -> paymentService.pay(60L, c.orderId(), "card", null, "OK-" + c.orderId()))
                .isInstanceOf(BusinessException.class);

        // 롤백으로 주문은 PAID로 확정되지 않음(PENDING 유지): "PAID인데 좌석 없음" 방지
        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(seatRepository.findById(c.seatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(holdRepository.findById(c.holdId()).orElseThrow().getStatus()).isEqualTo(SeatHoldStatus.EXPIRED);
    }

    @Test
    void 결제_확정은_같은_트랜잭션에_아웃박스_이벤트를_적재한다() {
        // ADR-010: 이벤트가 결제와 같은 커밋에 남아야(커밋 후 별도 발행 X)
        // "커밋됐는데 발행 전 크래시"에도 릴레이가 나중에 재발행할 수 있다(유실 0).
        Ctx c = order(70L, 1);

        paymentService.pay(70L, c.orderId(), "card", null, "OK-" + c.orderId());

        assertThat(outboxRepository.countByStatus(com.flowticket.outbox.domain.OutboxStatus.PENDING))
                .isEqualTo(1);
        com.flowticket.outbox.domain.OutboxEvent row = outboxRepository.findAll().get(0);
        assertThat(row.getType()).isEqualTo("order.paid");
        assertThat(row.getAggregateId()).isEqualTo(c.orderId());
        assertThat(row.getPayload()).contains(String.valueOf(row.getId())); // 행 PK == 멱등 키
    }

    @Test
    void 확정이_롤백되면_아웃박스_행도_남지_않는다() {
        // 유령 이벤트 0: 좌석이 이미 풀려 확정 실패 → 트랜잭션 롤백 → 아웃박스 행도 함께 사라짐.
        Ctx c = order(71L, 1);
        jdbc.update("update seats set status='AVAILABLE' where id=?", c.seatId());
        jdbc.update("update seat_holds set status='EXPIRED' where id=?", c.holdId());

        assertThatThrownBy(() -> paymentService.pay(71L, c.orderId(), "card", null, "OK-" + c.orderId()))
                .isInstanceOf(BusinessException.class);

        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void 만료로_확정실패시_이미난_PG승인을_보상취소한다() {
        // TS-011 3): PG 승인은 났는데 좌석이 만료 sweep에 풀려 확정 불가 → 트랜잭션 롤백만으로는
        // 외부 PG에 미아 승인이 남는다. finalizePaid가 gateway.refund로 승인을 취소(void)해야 함.
        Ctx c = order(61L, 1);
        jdbc.update("update seats set status='AVAILABLE' where id=?", c.seatId());
        jdbc.update("update seat_holds set status='EXPIRED' where id=?", c.holdId());

        assertThatThrownBy(() -> paymentService.pay(61L, c.orderId(), "card", null, "OK-" + c.orderId()))
                .isInstanceOf(BusinessException.class);

        // 승인(approve) 직후 확정 실패 → 보상 취소(refund)가 정확히 1회 호출됐는지 검증
        verify(gateway, times(1)).approve(eq(c.orderId()), anyInt(), anyString(), any(), anyString());
        verify(gateway, times(1)).refund(anyString(), anyInt(), anyString());
        // 주문은 여전히 PAID로 확정되지 않음
        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void IMP010_결제_만료_양방향_레이스_before_after() {
        // IMP-010: 결제 확정 × 만료 sweep의 양방향 경쟁을 trials회 재현해 "불일치 수"를 before/after로 측정.
        // before = 무가드 sweep(정방향) + 영향행수 미검사 결제(반대방향), after = 조건부 가드 + 영향행수 검증.
        int trials = 10;
        int before = 0, after = 0;
        TransactionTemplate tx = new TransactionTemplate(txManager);

        for (int i = 0; i < trials; i++) {
            // ── 정방향: 결제 승리 후 만료 sweep ──
            // before: 무가드 release(구버전)가 SOLD 좌석을 AVAILABLE로 되돌림 → 결제 좌석 유실
            Long fb = takeHeldSeat();
            jdbc.update("update seats set status='SOLD' where id=?", fb);         // 결제 승리
            jdbc.update("update seats set status='AVAILABLE' where id=?", fb);    // 무가드 release
            if ("AVAILABLE".equals(seatStat(fb))) before++;
            // after: 조건부 releaseSeats(from=HELD) → SOLD는 0행 → 유지
            Long fa = takeHeldSeat();
            jdbc.update("update seats set status='SOLD' where id=?", fa);
            tx.executeWithoutResult(s ->
                    seatRepository.releaseSeats(List.of(fa), SeatStatus.AVAILABLE, SeatStatus.HELD));
            if ("AVAILABLE".equals(seatStat(fa))) after++;

            // ── 반대방향: 만료 sweep 승리 후 결제 ──
            // before: 좌석이 이미 풀렸는데 markPaid만 성공(영향행수 미검사) → 주문 PAID·좌석 AVAILABLE
            Ctx rb = order(3000 + i, 1);
            jdbc.update("update seats set status='AVAILABLE' where id=?", rb.seatId());
            jdbc.update("update seat_holds set status='EXPIRED' where id=?", rb.holdId());
            jdbc.update("update orders set status='PAID' where id=? and status='PENDING'", rb.orderId());
            if ("PAID".equals(orderStat(rb.orderId())) && !"SOLD".equals(seatStat(rb.seatId()))) before++;
            // after: 실제 결제 → finalizePaid 영향행수 검증 → 예외 → 롤백 → 주문 PAID 아님
            long raUser = 4000L + i;
            Ctx ra = order(raUser, 1);
            jdbc.update("update seats set status='AVAILABLE' where id=?", ra.seatId());
            jdbc.update("update seat_holds set status='EXPIRED' where id=?", ra.holdId());
            try {
                paymentService.pay(raUser, ra.orderId(), "card", null, "OK-" + ra.orderId());
            } catch (RuntimeException ignore) { /* 영향행수 불일치 → 롤백 예외 */ }
            if ("PAID".equals(orderStat(ra.orderId())) && !"SOLD".equals(seatStat(ra.seatId()))) after++;
        }

        // before: 정방향+반대방향 각 trial 1건씩 = 2*trials 불일치 / after: 0
        assertThat(before).isEqualTo(2 * trials);
        assertThat(after).isZero();
    }

    private Long takeHeldSeat() {
        Long id = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE).map(Seat::getId).findFirst().orElseThrow();
        jdbc.update("update seats set status='HELD' where id=?", id);
        return id;
    }

    private String seatStat(Long id) {
        return jdbc.queryForObject("select status from seats where id=?", String.class, id);
    }

    private String orderStat(Long id) {
        return jdbc.queryForObject("select status from orders where id=?", String.class, id);
    }

    private record Ctx(Long orderId, Long holdId, Long seatId) {}

    private Ctx order(long userId, int count) {
        String token = queueService.issue(userId, eventId).token();
        admissionService.admit(eventId);
        List<Long> ids = seatRepository.findByEventId(eventId).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(count).toList();
        Long holdId = seatService.hold(userId, eventId, ids, token).holdId();
        Long orderId = orderService.create(userId, holdId).orderId();
        return new Ctx(orderId, holdId, ids.get(0));
    }
}
