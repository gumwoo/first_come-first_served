package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.RefundAttempt;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.gateway.PaymentGateway.Inquiry;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.repository.RefundAttemptRepository;
import com.flowticket.order.repository.RefundRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.order.service.RefundReconciliationService;
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
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 환불 정산(ADR-011): PG에는 취소가 남았는데 우리는 결제 완료로 아는 주문을 맞추는지 검증.
 *
 * 이 상태는 RefundService의 PG 취소가 성공한 뒤 같은 트랜잭션의 DB 쓰기가 실패하면 생긴다.
 * 롤백된 트랜잭션은 흔적을 남기지 않으므로, 테스트에서는 PAID로 남은 주문에 "PG는 취소됨"을
 * 스텁해 같은 상태를 만든다.
 */
@SpringBootTest
class RefundReconciliationIntegrationTest extends IntegrationTestSupport {

    @Autowired RefundReconciliationService reconciliation;
    @Autowired PaymentService paymentService;
    @Autowired OrderService orderService;
    @Autowired SeatService seatService;
    @Autowired SeatSeeder seatSeeder;
    @Autowired QueueService queueService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RefundRepository refundRepository;
    @Autowired RefundAttemptRepository attemptRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired EventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;
    @SpyBean PaymentGateway gateway;

    @BeforeEach
    void clean() {
        attemptRepository.deleteAll();
        refundRepository.deleteAll();
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        holdItemRepository.deleteAll();
        holdRepository.deleteAll();
        seatRepository.deleteAll();
        priceRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void PG에만_있던_환불을_주문과_좌석에_반영한다() {
        Ctx c = paidOrder(70L);
        int refunded = c.amount() - 1000; // PG가 수수료 1,000원을 떼고 취소한 상태
        doReturn(Inquiry.canceled("PG-CANCEL-1", refunded, true)).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();

        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(seatRepository.findById(c.seatId()).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
        var refund = refundRepository.findAll().get(0);
        // 금액은 PG가 실제로 취소한 값을 그대로 기록한다(정책으로 다시 계산하지 않는다).
        assertThat(refund.getAmount()).isEqualTo(refunded);
        assertThat(refund.getFee()).isEqualTo(1000);
        assertThat(refund.getPgRefundTid()).isEqualTo("PG-CANCEL-1");
    }

    @Test
    void 두_번_돌아도_환불_행은_하나다() {
        Ctx c = paidOrder(71L);
        doReturn(Inquiry.canceled("PG-CANCEL-2", c.amount(), false)).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();
        reconciliation.reconcileOrphanCancellations();

        assertThat(refundRepository.findAll()).hasSize(1);
    }

    @Test
    void PG에_승인이_남아있으면_건드리지_않는다() {
        Ctx c = paidOrder(72L);
        doReturn(Inquiry.approved("PG-OK")).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();

        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(refundRepository.findAll()).isEmpty();
    }

    /**
     * 이 테스트가 이 변경의 핵심이다. 조회 실패를 "취소됨"이나 "승인 없음"과 같이 다루면
     * PG가 잠깐 흔들릴 때 정상 결제가 환불 처리된다.
     */
    @Test
    void PG_조회에_실패하면_아무것도_하지_않는다() {
        Ctx c = paidOrder(73L);
        doReturn(Inquiry.unknown()).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();

        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(refundRepository.findAll()).isEmpty();
    }

    @Test
    void 결제금액을_넘는_취소는_자동_수렴하지_않는다() {
        Ctx c = paidOrder(74L);
        doReturn(Inquiry.canceled("PG-CANCEL-3", c.amount() + 1, false)).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();

        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(refundRepository.findAll()).isEmpty();
    }

    @Test
    void 유예시간_안의_최근_시도는_조회하지_않는다() {
        Ctx c = paidOrder(75L);
        jdbc.update("update refund_attempts set created_at = now() where order_id = ?", c.orderId());

        reconciliation.reconcileOrphanCancellations();

        verify(gateway, never()).inquire(c.orderId());
    }

    /**
     * 후보 기준이 결제 시각이면 놓치는 경우. 환불 가능 여부는 공연일까지 남은 날로 정해지므로
     * 한 달 전에 결제한 주문도 오늘 환불된다. 기준은 환불 시도 시각이어야 한다.
     */
    @Test
    void 결제한지_오래된_주문도_환불_시도가_있으면_정산한다() {
        Ctx c = paidOrder(76L);
        jdbc.update("update orders set paid_at = now() - interval '30 days' where id = ?", c.orderId());
        doReturn(Inquiry.canceled("PG-CANCEL-OLD", c.amount(), false)).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();

        assertThat(orderRepository.findById(c.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
    }

    /** 확인된 시도는 후보에서 빠진다. 열린 채로 두면 매 틱 PG를 다시 부른다. */
    @Test
    void 수렴한_시도는_다시_조회하지_않는다() {
        Ctx c = paidOrder(77L);
        doReturn(Inquiry.canceled("PG-CANCEL-4", c.amount(), false)).when(gateway).inquire(c.orderId());

        reconciliation.reconcileOrphanCancellations();
        reconciliation.reconcileOrphanCancellations();

        verify(gateway, times(1)).inquire(c.orderId());
    }

    // --- helpers ---

    private record Ctx(Long orderId, Long seatId, int amount) {}

    /** 결제까지 끝낸 주문. 유예(기본 10분)를 넘기려고 결제 시각을 1시간 전으로 돌려 둔다. */
    private Ctx paidOrder(long userId) {
        Event e = eventRepository.save(Event.builder()
                .kopisId("RFRC" + userId).title("환불 정산 테스트").genre("연극")
                .status(EventStatus.ON_SALE).startDate(LocalDate.now().plusDays(30)).build());
        seatSeeder.seedForEvent(e.getId());

        String token = queueService.issue(userId, e.getId()).token();
        admissionService.admit(e.getId());
        List<Long> seatIds = seatRepository.findByEventId(e.getId()).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();
        Long holdId = seatService.hold(userId, e.getId(), seatIds, token).holdId();
        Long orderId = orderService.create(userId, holdId).orderId();
        int amount = orderRepository.findById(orderId).orElseThrow().getAmount();
        paymentService.pay(userId, orderId, "card", null, "OK-" + orderId);

        // 환불을 시도했다가 PG 취소 후 DB 쓰기가 실패한 상태를 만든다. 롤백돼도 시도 기록은 남는다.
        attemptRepository.save(RefundAttempt.builder()
                .orderId(orderId).idempotencyKey("R-" + orderId).build());
        jdbc.update("update refund_attempts set created_at = now() - interval '1 hour' "
                + "where order_id = ?", orderId);
        return new Ctx(orderId, seatIds.get(0), amount);
    }
}
