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
import org.springframework.beans.factory.annotation.Value;
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
    /** 취소 전이의 패자가 승자의 기록을 기다리는 상한. PG 응답 시간보다 짧게 잡는다. */
    private final long duplicateWaitMs;

    private static final long WINNER_POLL_MS = 50;

    public RefundService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                         PaymentRepository paymentRepository, RefundRepository refundRepository,
                         RefundAttemptRepository refundAttemptRepository,
                         SeatRepository seatRepository, EventRepository eventRepository,
                         RefundPolicy refundPolicy, PaymentGateway gateway,
                         OrderSseRegistry orderSse, TransactionTemplate tx, Clock clock,
                         @Value("${payment.duplicate-wait-ms:5000}") long duplicateWaitMs) {
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
        this.duplicateWaitMs = duplicateWaitMs;
    }

    /**
     * 환불 진입.
     *
     * 결제와 같은 이유로 세 구간으로 나눈다(ADR-020·ADR-021). PG 취소를 기다리는 동안 DB 커넥션을
     * 쥐고 있지 않는다.
     *   TX1  검증 + 정책 계산 + 원자 전이 PAID→CANCELLED   — 이 요청이 취소의 주인인지 여기서 갈린다
     *   (밖) PG 취소 요청
     *   TX2  환불 기록 + 좌석 복구 + CANCELLED→REFUNDED
     *
     * 시도 기록은 그 앞에 남긴다(ADR-011). TX2가 실패하면 PG에만 취소가 남는데, 그 상태를 찾는
     * 유일한 단서가 이 행이다.
     */
    public RefundResponse refund(Long userId, Long orderId, String reason, String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // 시도 기록 전에 소유자를 본다. 남의 주문 ID로도 기록이 쌓여 정산 후보를 오염시킨다.
        // 판정의 진실원은 TX1의 ownedOrder다(여기 통과해도 트랜잭션 안에서 다시 본다).
        ownedOrder(orderId, userId);
        refundAttemptRepository.record(orderId, idemKey, LocalDateTime.now(clock));
        rejectIfKeyBelongsToAnotherOrder(orderId, idemKey);
        try {
            Started started = tx.execute(status -> startRefund(userId, orderId, idemKey));
            if (started.done() != null) {
                // 이미 환불된 건(순차 더블클릭). 어긋난 것이 없으므로 시도를 닫는다.
                refundAttemptRepository.resolve(orderId, idemKey);
                return started.done();
            }
            if (!started.owner()) {
                return awaitWinner(orderId, idemKey);
            }
            ApproveResult res = gateway.refund(started.pgTid(), started.quote().refundAmount(), idemKey);
            if (!res.success()) {
                // PG가 거절했다. 돈이 움직이지 않았으므로 취소 전이를 되돌린다.
                // 예전에는 한 트랜잭션이라 롤백이 이 일을 대신했다.
                tx.executeWithoutResult(status -> orderRepository.revertCancel(orderId));
                refundAttemptRepository.resolve(orderId, idemKey);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            RefundResponse out = tx.execute(status ->
                    completeRefund(orderId, started, reason, idemKey, res.pgTid()));
            // 정상 완료: PG와 DB가 일치하므로 정산이 볼 필요가 없다.
            refundAttemptRepository.resolve(orderId, idemKey);
            return out;
        } catch (DataIntegrityViolationException e) {
            return refundRepository.findByIdempotencyKey(idemKey)
                    .map(r -> RefundResponse.of(r, currentStatus(orderId).name()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
        }
    }

    /**
     * TX1의 결과.
     *   done   이미 환불이 끝난 건이라 돌려줄 결과가 있다
     *   owner  이 요청이 PAID→CANCELLED 전이의 주인이다. 아니면 승자를 기다려야 한다
     */
    private record Started(RefundQuote quote, Long paymentId, String pgTid,
                           boolean owner, RefundResponse done) {}

    /**
     * TX1: 환불 가능 여부를 보고 취소 전이를 잡는다.
     *
     * 전이가 refunds INSERT보다 먼저라는 순서는 그대로다([[TS-030]]). 달라진 것은 그 사이에 PG 호출이
     * 끼면서 전이와 기록이 **다른 트랜잭션**이 됐다는 점이고, 그래서 패자는 승자의 기록이 보일 때까지
     * 기다려야 한다(awaitWinner).
     */
    private Started startRefund(Long userId, Long orderId, String idemKey) {
        Order order = ownedOrder(orderId, userId);

        // 멱등: 같은 환불 시도가 이미 끝나 있으면 그 결과 반환(순차 더블클릭 방어)
        var dup = refundRepository.findByIdempotencyKey(idemKey);
        if (dup.isPresent()) {
            return new Started(null, null, null, false, RefundResponse.of(dup.get(), order.getStatus().name()));
        }

        // 상태 + 시점 게이트: PAID 아니거나 환불 불가 시점(당일·이후)이면 거부
        RefundQuote q = refundPolicy.quote(order.getAmount(), eventDate(order), LocalDateTime.now(clock));
        if (order.getStatus() != OrderStatus.PAID || !q.refundable()) {
            throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
        }

        // 원자 전이 PAID→CANCELLED (동시 환불 방어: 1행이면 이 요청이 취소의 주인)
        if (orderRepository.markCancelled(orderId, OrderStatus.PAID) != 1) {
            return new Started(q, null, null, false, null);
        }
        // 원 결제(APPROVED)의 거래 ID. PG 취소는 이 트랜잭션 밖에서 한다.
        Payment paid = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.APPROVED)
                .orElse(null);
        return new Started(q, paid != null ? paid.getId() : null,
                paid != null ? paid.getPgTid() : null, true, null);
    }

    /** TX2: 환불을 기록하고 좌석을 돌려놓는다. 이 구간이 끝나야 주문이 REFUNDED가 된다. */
    private RefundResponse completeRefund(Long orderId, Started started, String reason,
                                          String idemKey, String pgRefundTid) {
        // 환불 기록: 좌석 복구(벌크 UPDATE) 전에 flush로 확정(TS-007/010, 컨텍스트 클리어 유실 방지)
        Refund refund = refundRepository.save(Refund.builder()
                .orderId(orderId)
                .paymentId(started.paymentId())
                .amount(started.quote().refundAmount()).fee(started.quote().fee()).reason(reason)
                .pgRefundTid(pgRefundTid).idempotencyKey(idemKey).build());
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

    /**
     * 취소 전이의 주인이 아닌 요청이 승자의 결과를 받게 한다([[TS-030]]).
     *
     * 예전에는 전이와 refunds INSERT가 한 트랜잭션이라, 조건부 UPDATE가 승자의 행 락을 기다린
     * 시점에는 승자 행이 이미 커밋돼 보였다. 지금은 그 사이에 PG 취소가 들어가 승자가 아직
     * 기록 전일 수 있다. 그대로 REFUND_NOT_ALLOWED를 내면 더블클릭한 쪽만 실패한다.
     *
     * 주문이 더 이상 CANCELLED가 아니면 기다릴 이유가 없다. 다른 멱등키로 이미 환불됐거나
     * (REFUNDED) PG 거절로 되돌려진(PAID) 경우이고, 둘 다 이 요청에는 환불 불가다.
     */
    private RefundResponse awaitWinner(Long orderId, String idemKey) {
        long deadline = System.nanoTime() + duplicateWaitMs * 1_000_000L;
        while (true) {
            var done = refundRepository.findByIdempotencyKey(idemKey);
            if (done.isPresent()) {
                return RefundResponse.of(done.get(), currentStatus(orderId).name());
            }
            if (currentStatus(orderId) != OrderStatus.CANCELLED || System.nanoTime() >= deadline) {
                throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
            }
            try {
                Thread.sleep(WINNER_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
            }
        }
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
