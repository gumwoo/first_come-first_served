package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 환불 정산이 후보를 끝까지 도는지 검증한다.
 *
 * 정상 주문은 PG에 물어봐도 PAID로 남는다. 그래서 정렬 키가 결제 시각이면 매 틱 같은 앞쪽
 * 묶음만 다시 뽑히고, 뒤쪽 주문은 소급 한계(24시간) 밖으로 밀려날 때까지 한 번도 조회되지
 * 않는다. 후보 수가 배치보다 많을 때만 드러나므로 배치를 2로 줄여 재현한다.
 *
 * 컨텍스트를 따로 띄우는 비용을 감수하고 @TestPropertySource를 쓴다. 이 순회 규칙은 후보가
 * 배치를 넘는 상황에서만 의미가 있는데, 운영 기본값(50)으로 재현하려면 주문 51건이 필요하다.
 */
@SpringBootTest
@TestPropertySource(properties = "refund.reconcile-batch-size=2")
class RefundReconciliationRotationIntegrationTest extends IntegrationTestSupport {

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
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository holdRepository;
    @Autowired SeatHoldItemRepository holdItemRepository;
    @Autowired EventSeatPriceRepository priceRepository;
    @Autowired EventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;
    @SpyBean PaymentGateway gateway;

    @BeforeEach
    void clean() {
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

    /**
     * 후보 3건, 배치 2건. 두 틱이면 세 건 모두 한 번씩 조회돼야 한다.
     *
     * 결제 시각 정렬이던 예전 구현에서는 두 번째 틱이 또 앞의 두 건을 집어, 세 번째 주문은
     * 영원히 조회되지 않는다. Mock 게이트웨이는 "결제 없음"을 돌려주므로 상태는 바뀌지 않고
     * 순회 규칙만 드러난다.
     */
    @Test
    void 후보가_배치보다_많아도_모두_조회된다() {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            orderIds.add(paidOrder(80L + i, i));
        }

        reconciliation.reconcileOrphanCancellations();
        reconciliation.reconcileOrphanCancellations();

        for (Long orderId : orderIds) {
            verify(gateway, times(1)).inquire(orderId);
        }
    }

    /** 조회한 주문에는 표시가 남아야 다음 틱이 다음 묶음으로 넘어간다. */
    @Test
    void 조회한_주문에는_조회시각이_남는다() {
        Long orderId = paidOrder(90L, 0);

        reconciliation.reconcileOrphanCancellations();

        Long marked = jdbc.queryForObject(
                "select count(*) from orders where id = ? and refund_checked_at is not null",
                Long.class, orderId);
        assertThat(marked).isEqualTo(1L);
    }

    // --- helpers ---

    /** 결제까지 끝낸 주문. 유예(기본 10분)를 넘기려고 결제 시각을 과거로 돌린다. */
    private Long paidOrder(long userId, int ageMinutes) {
        Event e = eventRepository.save(Event.builder()
                .kopisId("RFRT" + userId).title("정산 순회 테스트").genre("연극")
                .status(EventStatus.ON_SALE).startDate(LocalDate.now().plusDays(30)).build());
        seatSeeder.seedForEvent(e.getId());

        String token = queueService.issue(userId, e.getId()).token();
        admissionService.admit(e.getId());
        List<Long> seatIds = seatRepository.findByEventId(e.getId()).stream()
                .filter(s -> s.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getId).limit(1).toList();
        Long holdId = seatService.hold(userId, e.getId(), seatIds, token).holdId();
        Long orderId = orderService.create(userId, holdId).orderId();
        paymentService.pay(userId, orderId, "card", null, "OK-" + orderId);

        // 결제 시각을 서로 다르게 둔다. 예전 구현의 정렬 키가 이 값이었다.
        jdbc.update("update orders set paid_at = now() - interval '1 hour' - (? * interval '1 minute') "
                + "where id = ?", ageMinutes, orderId);
        return orderId;
    }
}
