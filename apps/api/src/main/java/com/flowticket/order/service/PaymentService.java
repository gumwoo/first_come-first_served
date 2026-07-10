package com.flowticket.order.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderItem;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.Payment;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.gateway.PaymentGateway;
import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatHoldRepository;
import com.flowticket.seat.repository.SeatRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 승인(card/easy). 게이트웨이 승인 → 조건부 주문전이(PENDING→PAID) → 좌석 SOLD·hold CONVERTED.
 * 멱등: 같은 idempotencyKey는 재처리하지 않고 기존 결과 반환(ADR-006).
 */
@Service
public class PaymentService {

    private static final Set<String> IMMEDIATE = Set.of("card", "easy");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository holdRepository;
    private final PaymentGateway gateway;
    private final OrderSseRegistry orderSse;

    public PaymentService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                          PaymentRepository paymentRepository, SeatRepository seatRepository,
                          SeatHoldRepository holdRepository, PaymentGateway gateway,
                          OrderSseRegistry orderSse) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.gateway = gateway;
        this.orderSse = orderSse;
    }

    @Transactional
    public PaymentResponse pay(Long userId, Long orderId, String method, String provider, String idemKey) {
        if (idemKey == null || idemKey.isBlank() || method == null || !IMMEDIATE.contains(method)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        // 멱등: 같은 결제 시도가 이미 있으면 그 결과 반환(순차 더블클릭 방어)
        var dup = paymentRepository.findByIdempotencyKey(idemKey);
        if (dup.isPresent()) {
            return new PaymentResponse(dup.get().getId(), dup.get().getStatus().name(), order.getStatus().name());
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION); // 이미 PAID/EXPIRED 등
        }
        if (order.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PAYMENT_TIMEOUT);
        }

        Payment payment = paymentRepository.save(Payment.builder()
                .orderId(orderId).method(method).provider(provider)
                .amount(order.getAmount()).idempotencyKey(idemKey).build());

        ApproveResult res = gateway.approve(orderId, order.getAmount(), method, provider, idemKey);
        if (!res.success()) {
            payment.fail();
            paymentRepository.save(payment); // FAILED만 기록, 주문은 PENDING 유지(재시도 가능)
            return new PaymentResponse(payment.getId(), payment.getStatus().name(), order.getStatus().name());
        }

        // 승인 상태를 먼저 확정 반영(벌크 UPDATE의 컨텍스트 클리어 전에, TS-007 교훈)
        payment.approve(res.pgTid());
        paymentRepository.saveAndFlush(payment);

        int updated = orderRepository.markPaid(orderId); // PENDING→PAID 조건부(원자)
        if (updated == 1) {
            List<Long> seatIds = orderItemRepository.findByOrderId(orderId).stream()
                    .map(OrderItem::getSeatId).toList();
            seatRepository.sellSeats(seatIds, SeatStatus.SOLD, SeatStatus.HELD); // HELD→SOLD
            holdRepository.convertHold(order.getHoldId());                        // HELD→CONVERTED
            orderSse.broadcast(orderId, "order.paid", Map.of("orderId", orderId));
        }
        // 최신 주문 상태로 응답(경합 시 updated=0이어도 이미 PAID)
        OrderStatus finalStatus = orderRepository.findById(orderId)
                .map(Order::getStatus).orElse(OrderStatus.PAID);
        return new PaymentResponse(payment.getId(), payment.getStatus().name(), finalStatus.name());
    }
}
