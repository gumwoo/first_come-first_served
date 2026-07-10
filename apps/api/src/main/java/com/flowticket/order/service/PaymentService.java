package com.flowticket.order.service;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderItem;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.Payment;
import com.flowticket.order.domain.PaymentStatus;
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
 * 결제 승인(card/easy 즉시, vbank 가상계좌+입금확인). 게이트웨이 승인 → 조건부 주문전이 →
 * 좌석 SOLD·hold CONVERTED. 멱등: 같은 idempotencyKey는 재처리하지 않음(ADR-006).
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
        if (idemKey == null || idemKey.isBlank() || method == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        Order order = ownedOrder(orderId, userId);

        // 멱등: 같은 결제 시도가 이미 있으면 그 결과 반환(순차 더블클릭 방어)
        var dup = paymentRepository.findByIdempotencyKey(idemKey);
        if (dup.isPresent()) {
            return PaymentResponse.of(dup.get().getId(), dup.get().getStatus().name(), order.getStatus().name());
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (order.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PAYMENT_TIMEOUT);
        }

        Payment payment = paymentRepository.save(Payment.builder()
                .orderId(orderId).method(method).provider(provider)
                .amount(order.getAmount()).idempotencyKey(idemKey).build());

        if ("vbank".equals(method)) {
            String account = gateway.issueVbank(orderId, order.getAmount());
            payment.assignVbank(account, order.getExpiresAt()); // 입금기한 = 결제 제한시각
            paymentRepository.save(payment);
            orderRepository.markVbankWaiting(orderId); // PENDING→VBANK_WAITING
            return PaymentResponse.vbank(payment.getId(), OrderStatus.VBANK_WAITING.name(),
                    account, order.getExpiresAt());
        }
        if (!IMMEDIATE.contains(method)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }

        ApproveResult res = gateway.approve(orderId, order.getAmount(), method, provider, idemKey);
        if (!res.success()) {
            payment.fail();
            paymentRepository.save(payment); // FAILED만 기록, 주문 PENDING 유지(재시도 가능)
            return PaymentResponse.of(payment.getId(), payment.getStatus().name(), order.getStatus().name());
        }
        finalizePaid(order, payment, OrderStatus.PENDING, res.pgTid());
        return PaymentResponse.of(payment.getId(), PaymentStatus.APPROVED.name(), currentStatus(orderId).name());
    }

    /** 무통장 입금 확인(개발/데모 트리거 · 실제는 PG 웹훅 BE-5). VBANK_WAITING→PAID로 확정. */
    @Transactional
    public PaymentResponse confirmVbankDeposit(Long userId, Long orderId) {
        Order order = ownedOrder(orderId, userId);
        if (order.getStatus() != OrderStatus.VBANK_WAITING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
        }
        Payment payment = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.READY)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        orderSse.broadcast(orderId, "payment.vbank.deposited", Map.of("orderId", orderId));
        finalizePaid(order, payment, OrderStatus.VBANK_WAITING, "DEV-DEPOSIT");
        return PaymentResponse.of(payment.getId(), PaymentStatus.APPROVED.name(), currentStatus(orderId).name());
    }

    /** 승인 확정: payment APPROVED(먼저 flush) → 주문 조건부 전이 → 좌석 SOLD·hold CONVERTED → order.paid. */
    private void finalizePaid(Order order, Payment payment, OrderStatus from, String pgTid) {
        payment.approve(pgTid);
        paymentRepository.saveAndFlush(payment); // 벌크 UPDATE의 컨텍스트 클리어 전에 확정(TS-007)

        int updated = orderRepository.markPaid(order.getId(), from);
        if (updated == 1) {
            List<Long> seatIds = orderItemRepository.findByOrderId(order.getId()).stream()
                    .map(OrderItem::getSeatId).toList();
            seatRepository.sellSeats(seatIds, SeatStatus.SOLD, SeatStatus.HELD);
            holdRepository.convertHold(order.getHoldId());
            orderSse.broadcast(order.getId(), "order.paid", Map.of("orderId", order.getId()));
        }
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
        return orderRepository.findById(orderId).map(Order::getStatus).orElse(OrderStatus.PAID);
    }
}
