package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowticket.support.IntegrationTestSupport;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.repository.RefundAttemptRepository;
import com.flowticket.order.repository.RefundRepository;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.PaymentService;
import com.flowticket.order.service.RefundService;
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

/**
 * 환불 시도 기록(ADR-011)이 정산 안전망으로 성립하는지 검증한다.
 *
 * 기록이 남지 않은 채 PG 취소가 나가면 이 PR이 만든 정산이 그 건을 영영 못 본다. 그래서
 * "PG를 부르기 전에 기록이 있다"가 이 테이블의 유일한 존재 이유다.
 */
@SpringBootTest
class RefundAttemptIntegrationTest extends IntegrationTestSupport {

    @Autowired RefundService refundService;
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

    /**
     * 멱등키가 컬럼 폭(80)을 넘으면 기록 INSERT가 실패한다. 그 실패를 "중복이겠지"로 삼키면
     * PG 취소가 나간 뒤 refunds INSERT가 같은 이유로 실패해, 돈은 빠져나가고 DB에는 주문도
     * 시도도 남지 않는다 — 정산이 볼 것이 없어진다. 그래서 PG를 부르기 전에 멈춰야 한다.
     */
    @Test
    void 멱등키가_컬럼폭을_넘으면_PG를_부르기_전에_멈춘다() {
        long user = 110L;
        Long orderId = paidOrder(user);
        String tooLong = "K".repeat(81);

        assertThatThrownBy(() -> refundService.refund(user, orderId, "변심", tooLong))
                .isInstanceOf(RuntimeException.class);

        verify(gateway, never()).refund(anyString(), anyInt(), anyString());
        assertThat(attemptRepository.findAll()).isEmpty();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    /** 남의 주문 ID로도 기록이 쌓이면 정산 후보가 오염된다. 소유자 검증이 기록보다 앞이다. */
    @Test
    void 남의_주문에는_시도_기록을_남기지_않는다() {
        Long orderId = paidOrder(111L);

        assertThatThrownBy(() -> refundService.refund(999L, orderId, "변심", "R-INTRUDER"))
                .isInstanceOf(BusinessException.class);

        assertThat(attemptRepository.findAll()).isEmpty();
        verify(gateway, never()).refund(anyString(), anyInt(), anyString());
    }

    /** 정상 환불은 PG와 DB가 일치하므로 정산이 볼 필요가 없다. */
    @Test
    void 정상_환불은_시도가_확인됨으로_닫힌다() {
        long user = 112L;
        Long orderId = paidOrder(user);

        refundService.refund(user, orderId, "변심", "R-OK-" + orderId);

        assertThat(attemptRepository.findAll()).hasSize(1);
        assertThat(attemptRepository.findAll().get(0).isResolved()).isTrue();
    }

    /** 더블클릭·재시도. 같은 멱등키는 후보로 한 번만 올라간다. */
    @Test
    void 같은_멱등키를_두_번_보내도_시도는_하나다() {
        long user = 113L;
        Long orderId = paidOrder(user);
        String key = "R-DUP-" + orderId;

        refundService.refund(user, orderId, "변심", key);
        refundService.refund(user, orderId, "변심", key);

        assertThat(attemptRepository.findAll()).hasSize(1);
        assertThat(refundRepository.findAll()).hasSize(1);
    }

    /**
     * 멱등키는 클라이언트가 만들고 UNIQUE는 전역이다. 앞 주문의 시도가 미해결로 남은 상태에서
     * 다른 주문이 같은 키를 쓰면, 그 요청은 기록 없이 PG를 부르고 성공 시 앞 주문의 시도까지
     * 닫아 버린다. 그러면 앞 주문의 미아 취소는 영영 정산되지 않는다.
     */
    @Test
    void 다른_주문의_멱등키를_재사용하면_PG를_부르기_전에_거절한다() {
        long userA = 114L;
        long userB = 115L;
        Long orderA = paidOrder(userA);
        Long orderB = paidOrder(userB);
        String key = "K-SHARED";
        // A가 PG 취소 후 롤백된 상태: 시도만 남고 refunds에는 아무것도 없다.
        attemptRepository.record(orderA, key, java.time.LocalDateTime.now());

        assertThatThrownBy(() -> refundService.refund(userB, orderB, "변심", key))
                .isInstanceOf(BusinessException.class);

        verify(gateway, never()).refund(anyString(), anyInt(), anyString());
        assertThat(attemptRepository.findByIdempotencyKey(key).orElseThrow().isResolved()).isFalse();
        assertThat(attemptRepository.findByIdempotencyKey(key).orElseThrow().getOrderId())
                .isEqualTo(orderA);
    }

    /**
     * PG 취소 결과를 모를 때는 되돌리지도, 시도를 닫지도 않는다(ADR-021).
     *
     * 타임아웃·5xx는 "취소하지 않았다"가 아니다. 실제로 취소됐는데 응답만 못 받았을 수 있어,
     * 여기서 주문을 PAID로 되돌리고 시도까지 닫으면 돈은 나갔는데 장부는 결제 완료로 남고
     * 그 상태를 찾아낼 단서도 사라진다. 정산이 PG에 물어 정리하도록 열어 둔다.
     */
    @Test
    void PG_취소결과를_모르면_되돌리지_않고_정산에_넘긴다() {
        long user = 116L;
        Long orderId = paidOrder(user);
        doReturn(PaymentGateway.ApproveResult.unknown("타임아웃"))
                .when(gateway).refund(anyString(), anyInt(), anyString());

        assertThatThrownBy(() -> refundService.refund(user, orderId, "변심", "R-UNK-" + orderId))
                .isInstanceOf(BusinessException.class);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .as("되돌리면 PG에만 남은 취소를 영영 못 찾는다").isEqualTo(OrderStatus.CANCELLED);
        assertThat(attemptRepository.findAll().get(0).isResolved())
                .as("정산이 다시 봐야 하므로 시도는 열어 둔다").isFalse();
    }

    // --- helpers ---

    private Long paidOrder(long userId) {
        Event e = eventRepository.save(Event.builder()
                .kopisId("RFAT" + userId).title("환불 시도 테스트").genre("연극")
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
        return orderId;
    }
}
