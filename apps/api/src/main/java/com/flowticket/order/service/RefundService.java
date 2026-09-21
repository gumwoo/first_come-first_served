package com.flowticket.order.service;

import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderItem;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.Payment;
import com.flowticket.order.domain.PaymentStatus;
import com.flowticket.order.domain.Refund;
import com.flowticket.order.dto.RefundResponse;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.repository.RefundAttemptRepository;
import com.flowticket.order.repository.RefundRepository;
import com.flowticket.order.service.RefundPolicy.RefundQuote;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예매 취소·환불. PAID + 시점 게이트에서만 가능. 조건부 전이(PAID→CANCELLED→REFUNDED)로 원자화하고
 * 좌석을 SOLD→AVAILABLE 복구한다. 멱등: 더블클릭/재전송에도 이중 환불·이중 복구 0(ADR-006).
 */
@Service
public class RefundService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final RefundAttemptRepository refundAttemptRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final RefundPolicy refundPolicy;
    private final PaymentGateway gateway;
    private final OrderSseRegistry orderSse;
    /** 트랜잭션 경계를 코드로 연다. 커밋에서 나는 멱등키 충돌을 경계 밖에서 잡아야 하기 때문이다. */
    private final TransactionTemplate tx;

    private final Clock clock;

    public RefundService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                         PaymentRepository paymentRepository, RefundRepository refundRepository,
                         RefundAttemptRepository refundAttemptRepository,
                         SeatRepository seatRepository, EventRepository eventRepository,
                         RefundPolicy refundPolicy, PaymentGateway gateway,
                         OrderSseRegistry orderSse, TransactionTemplate tx, Clock clock) {
        this.clock = clock;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.refundAttemptRepository = refundAttemptRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.refundPolicy = refundPolicy;
        this.gateway = gateway;
        this.orderSse = orderSse;
        this.tx = tx;
    }

    /**
     * 환불 진입. 동시 같은 idempotencyKey(더블클릭)로 UNIQUE 충돌이 나면:
     * 이미 다른 스레드가 처리한 것이므로 기존 결과를 멱등하게 반환.
     *
     * 시도 기록을 먼저 남긴다(ADR-011). refundTx는 PG 취소와 DB 쓰기를 한 트랜잭션에 묶으므로,
     * PG 취소 성공 뒤 쓰기가 실패하면 전체가 롤백돼 "환불을 시도했다"는 사실까지 사라진다.
     * 이 행은 그 트랜잭션이 열리기 전에 별도로 커밋돼 정산의 후보가 된다.
     */
    public RefundResponse refund(Long userId, Long orderId, String reason, String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // 시도 기록 전에 소유자를 본다. 남의 주문 ID로도 기록이 쌓여 정산 후보를 오염시킨다.
        // 판정의 진실원은 refundTx의 ownedOrder다(여기 통과해도 트랜잭션 안에서 다시 본다).
        ownedOrder(orderId, userId);
        refundAttemptRepository.record(orderId, idemKey, LocalDateTime.now(clock));
        rejectIfKeyBelongsToAnotherOrder(orderId, idemKey);
        try {
            RefundResponse res = tx.execute(status -> refundTx(userId, orderId, reason, idemKey));
            // 정상 완료: PG와 DB가 일치하므로 정산이 볼 필요가 없다.
            refundAttemptRepository.resolve(orderId, idemKey);
            return res;
        } catch (DataIntegrityViolationException e) {
            return refundRepository.findByIdempotencyKey(idemKey)
                    .map(r -> RefundResponse.of(r, currentStatus(orderId).name()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        }
    }

    /** 환불 본문. 반드시 refund()의 트랜잭션 경계 안에서 호출된다. */
    private RefundResponse refundTx(Long userId, Long orderId, String reason, String idemKey) {
        Order order = ownedOrder(orderId, userId);

        // 멱등: 같은 환불 시도가 이미 있으면 그 결과 반환(순차 더블클릭 방어)
        var dup = refundRepository.findByIdempotencyKey(idemKey);
        if (dup.isPresent()) {
            return RefundResponse.of(dup.get(), order.getStatus().name());
        }

        // 상태 + 시점 게이트: PAID 아니거나 환불 불가 시점(당일·이후)이면 거부
        RefundQuote q = refundPolicy.quote(order.getAmount(), eventDate(order), LocalDateTime.now(clock));
        if (order.getStatus() != OrderStatus.PAID || !q.refundable()) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
        }

        // 원자 전이 PAID→CANCELLED (동시 환불 방어: 1행이면 이 요청이 취소의 주인)
        int cancelled = orderRepository.markCancelled(orderId, OrderStatus.PAID);
        if (cancelled != 1) {
            // 취소의 주인이 아니어도 실패는 아니다. 같은 멱등키의 동시 요청이면 승자의 결과를 돌려준다.
            // 환불은 상태 전이가 refunds INSERT보다 먼저라 패자가 UNIQUE에 도달하지 못한다(TS-030).
            // 조건부 UPDATE가 승자의 행 락을 기다렸으므로 이 시점에는 승자 행이 이미 커밋돼 보인다.
            return refundRepository.findByIdempotencyKey(idemKey)
                    .map(r -> RefundResponse.of(r, currentStatus(orderId).name()))
                    // 다른 멱등키로 이미 환불됐거나 취소된 주문: 이건 진짜로 환불 불가다.
                    .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_ALLOWED));
        }

        // 원 결제(APPROVED) 취소: PG 환불
        Payment paid = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.APPROVED)
                .orElse(null);
        String pgTid = paid != null ? paid.getPgTid() : null;
        ApproveResult res = gateway.refund(pgTid, q.refundAmount(), idemKey);
        if (!res.success()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR); // 롤백 → CANCELLED 전이도 되돌림
        }

        // 환불 기록: 좌석 복구(벌크 UPDATE) 전에 flush로 확정(TS-007/010, 컨텍스트 클리어 유실 방지)
        Refund refund = refundRepository.save(Refund.builder()
                .orderId(orderId)
                .paymentId(paid != null ? paid.getId() : null)
                .amount(q.refundAmount()).fee(q.fee()).reason(reason)
                .pgRefundTid(res.pgTid()).idempotencyKey(idemKey).build());
        refundRepository.saveAndFlush(refund);

        // 좌석 SOLD→AVAILABLE 복구
        List<Long> seatIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getSeatId).toList();
        seatRepository.releaseSeats(seatIds, SeatStatus.AVAILABLE, SeatStatus.SOLD); // 환불: SOLD→AVAILABLE

        // CANCELLED→REFUNDED 확정
        orderRepository.markRefunded(orderId, OrderStatus.CANCELLED);
        orderSse.broadcast(orderId, "order.cancelled", Map.of("orderId", orderId));
        orderSse.broadcast(orderId, "order.refunded", Map.of("orderId", orderId));

        return RefundResponse.of(refund, OrderStatus.REFUNDED.name());
    }

    private LocalDate eventDate(Order order) {
        return eventRepository.findById(order.getEventId())
                .map(e -> e.getStartDate()).orElse(null);
    }

    /**
     * 같은 멱등키가 다른 주문에 묶여 있으면 PG를 부르기 전에 거절한다.
     *
     * 시도 기록은 키 UNIQUE라 재사용 요청은 행을 만들지 못한다. 그대로 진행시키면 그 요청은
     * 기록 없이 PG를 호출하게 되고, 성공하면 앞 주문의 미해결 시도까지 닫아 정산이 그 건을
     * 영영 못 보게 된다. refunds의 키 UNIQUE는 앞 주문이 롤백된 경우 비어 있어 막아주지 못한다.
     */
    private void rejectIfKeyBelongsToAnotherOrder(Long orderId, String idemKey) {
        refundAttemptRepository.findByIdempotencyKey(idemKey)
                .filter(a -> !a.getOrderId().equals(orderId))
                .ifPresent(a -> {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
                });
    }

    private Order ownedOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return order;
    }

    private OrderStatus currentStatus(Long orderId) {
        return orderRepository.findById(orderId).map(Order::getStatus).orElse(OrderStatus.REFUNDED);
    }
}
