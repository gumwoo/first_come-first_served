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
import com.flowticket.order.repository.RefundRepository;
import com.flowticket.order.service.RefundPolicy.RefundQuote;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매 취소·환불(S06). PAID + 시점 게이트에서만 가능. 조건부 전이(PAID→CANCELLED→REFUNDED)로 원자화하고
 * 좌석을 SOLD→AVAILABLE 복구한다. 멱등: 더블클릭/재전송에도 이중 환불·이중 복구 0(ADR-006).
 */
@Service
public class RefundService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final RefundPolicy refundPolicy;
    private final PaymentGateway gateway;
    private final OrderSseRegistry orderSse;
    private final ObjectProvider<RefundService> self; // 트랜잭션 프록시 self-호출용

    public RefundService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                         PaymentRepository paymentRepository, RefundRepository refundRepository,
                         SeatRepository seatRepository, EventRepository eventRepository,
                         RefundPolicy refundPolicy, PaymentGateway gateway,
                         OrderSseRegistry orderSse, ObjectProvider<RefundService> self) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.refundPolicy = refundPolicy;
        this.gateway = gateway;
        this.orderSse = orderSse;
        this.self = self;
    }

    /**
     * 환불 진입. 동시 같은 idempotencyKey(더블클릭)로 UNIQUE 충돌이 나면 —
     * 이미 다른 스레드가 처리한 것이므로 기존 결과를 멱등하게 반환.
     */
    public RefundResponse refund(Long userId, Long orderId, String reason, String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return self.getObject().refundTx(userId, orderId, reason, idemKey);
        } catch (DataIntegrityViolationException e) {
            return refundRepository.findByIdempotencyKey(idemKey)
                    .map(r -> RefundResponse.of(r, currentStatus(orderId).name()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Transactional
    public RefundResponse refundTx(Long userId, Long orderId, String reason, String idemKey) {
        Order order = ownedOrder(orderId, userId);

        // 멱등: 같은 환불 시도가 이미 있으면 그 결과 반환(순차 더블클릭 방어)
        var dup = refundRepository.findByIdempotencyKey(idemKey);
        if (dup.isPresent()) {
            return RefundResponse.of(dup.get(), order.getStatus().name());
        }

        // 상태 + 시점 게이트: PAID 아니거나 환불 불가 시점(당일·이후)이면 거부
        RefundQuote q = refundPolicy.quote(order.getAmount(), eventDate(order), LocalDateTime.now());
        if (order.getStatus() != OrderStatus.PAID || !q.refundable()) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
        }

        // 원자 전이 PAID→CANCELLED (동시 환불 방어 — 1행이면 이 요청이 취소의 주인)
        int cancelled = orderRepository.markCancelled(orderId, OrderStatus.PAID);
        if (cancelled != 1) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED); // 이미 취소/환불됨
        }

        // 원 결제(APPROVED) 취소 — PG 환불
        Payment paid = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.APPROVED)
                .orElse(null);
        String pgTid = paid != null ? paid.getPgTid() : null;
        ApproveResult res = gateway.refund(pgTid, q.refundAmount());
        if (!res.success()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR); // 롤백 → CANCELLED 전이도 되돌림
        }

        // 환불 기록 — 좌석 복구(벌크 UPDATE) 전에 flush로 확정(TS-007/010: 컨텍스트 클리어 유실 방지)
        Refund refund = refundRepository.save(Refund.builder()
                .orderId(orderId)
                .paymentId(paid != null ? paid.getId() : null)
                .amount(q.refundAmount()).fee(q.fee()).reason(reason)
                .pgRefundTid(res.pgTid()).idempotencyKey(idemKey).build());
        refundRepository.saveAndFlush(refund);

        // 좌석 SOLD→AVAILABLE 복구
        List<Long> seatIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getSeatId).toList();
        seatRepository.releaseSeats(seatIds, SeatStatus.AVAILABLE);

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
