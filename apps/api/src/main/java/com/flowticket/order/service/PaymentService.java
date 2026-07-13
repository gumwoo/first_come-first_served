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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ObjectProvider<PaymentService> self; // 트랜잭션 프록시 self-호출용

    public PaymentService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                          PaymentRepository paymentRepository, SeatRepository seatRepository,
                          SeatHoldRepository holdRepository, PaymentGateway gateway,
                          OrderSseRegistry orderSse, ObjectProvider<PaymentService> self) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.seatRepository = seatRepository;
        this.holdRepository = holdRepository;
        this.gateway = gateway;
        this.orderSse = orderSse;
        this.self = self;
    }

    /**
     * 결제 진입. 동시 같은 idempotencyKey(더블클릭)로 UNIQUE 충돌이 나면 —
     * 이미 다른 스레드가 처리한 것이므로 기존 결과를 멱등하게 반환(이중 PAID/발급 0, IMP-008).
     */
    public PaymentResponse pay(Long userId, Long orderId, String method, String provider, String idemKey) {
        try {
            return self.getObject().payTx(userId, orderId, method, provider, idemKey);
        } catch (DataIntegrityViolationException e) {
            return paymentRepository.findByIdempotencyKey(idemKey)
                    .map(p -> PaymentResponse.of(p.getId(), p.getStatus().name(), currentStatus(orderId).name()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Transactional
    public PaymentResponse payTx(Long userId, Long orderId, String method, String provider, String idemKey) {
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
            PaymentGateway.VbankIssue vb = gateway.issueVbank(orderId, order.getAmount());
            payment.assignVbank(vb.account(), order.getExpiresAt(), vb.secret()); // 입금기한 = 결제 제한시각
            // 벌크 UPDATE(clearAutomatically)가 컨텍스트를 비우기 전에 계좌/기한/secret을 확정(TS-007)
            paymentRepository.saveAndFlush(payment);
            orderRepository.markVbankWaiting(orderId); // PENDING→VBANK_WAITING
            return PaymentResponse.vbank(payment.getId(), OrderStatus.VBANK_WAITING.name(),
                    vb.account(), order.getExpiresAt());
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

    /**
     * 결제창(Toss 등) 인증 후 서버 확정(BE-5). 클라이언트가 받은 paymentKey로 승인 API를 호출한다.
     * 멱등키는 결제창의 paymentKey(주문당 유일)를 사용 — 동시/재요청 시 UNIQUE로 이중 승인 차단.
     */
    public PaymentResponse confirm(Long userId, Long orderId, String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return self.getObject().confirmTx(userId, orderId, paymentKey);
        } catch (DataIntegrityViolationException e) {
            return paymentRepository.findByIdempotencyKey(paymentKey)
                    .map(p -> PaymentResponse.of(p.getId(), p.getStatus().name(), currentStatus(orderId).name()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        }
    }

    @Transactional
    public PaymentResponse confirmTx(Long userId, Long orderId, String paymentKey) {
        Order order = ownedOrder(orderId, userId);

        var dup = paymentRepository.findByIdempotencyKey(paymentKey);
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
                .orderId(orderId).method("card").provider("toss")
                .amount(order.getAmount()).idempotencyKey(paymentKey).build());

        ApproveResult res = gateway.confirm(orderId, paymentKey, order.getAmount());
        if (!res.success()) {
            payment.fail();
            paymentRepository.save(payment);
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

    /**
     * 가상계좌 입금 웹훅(Toss DEPOSIT_CALLBACK) 처리(BE-5). 위조·재전송을 방어한다.
     * - 검증: 발급 때 저장한 vbank_secret과 웹훅 secret 대조(불일치 → FORBIDDEN, HMAC 서명은 지급대행 전용).
     * - 멱등: Toss는 2xx 못 받으면 최대 7회 재전송 → 이미 PAID면 그대로 성공 응답(no-op).
     * - status가 완료(DONE)일 때만 VBANK_WAITING→PAID 확정.
     * tossOrderId는 결제창 규약 "FLOWTICKET-ORDER-{id}".
     */
    @Transactional
    public void handleVbankDepositWebhook(String tossOrderId, String status, String secret) {
        Long orderId = parseOrderId(tossOrderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        Payment payment = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.READY)
                .orElse(null);
        // 이미 확정됐거나(READY 없음=PAID) 재전송 → 멱등 no-op
        if (payment == null || order.getStatus() != OrderStatus.VBANK_WAITING) {
            return;
        }
        // 위조 검증: 저장 secret과 웹훅 secret 대조(발급분에 secret이 있을 때만 신뢰)
        if (payment.getVbankSecret() == null || !payment.getVbankSecret().equals(secret)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!"DONE".equals(status)) {
            return; // 입금 완료 상태가 아니면 대기 유지
        }
        orderSse.broadcast(orderId, "payment.vbank.deposited", Map.of("orderId", orderId));
        finalizePaid(order, payment, OrderStatus.VBANK_WAITING, "TOSS-DEPOSIT-" + payment.getId());
    }

    private Long parseOrderId(String tossOrderId) {
        if (tossOrderId == null || !tossOrderId.startsWith("FLOWTICKET-ORDER-")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return Long.parseLong(tossOrderId.substring("FLOWTICKET-ORDER-".length()));
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
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
