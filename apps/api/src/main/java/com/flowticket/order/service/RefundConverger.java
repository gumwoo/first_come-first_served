package com.flowticket.order.service;

import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderItem;
import com.flowticket.order.domain.OrderStatus;
import com.flowticket.order.domain.Payment;
import com.flowticket.order.domain.PaymentStatus;
import com.flowticket.order.domain.Refund;
import com.flowticket.order.repository.OrderItemRepository;
import com.flowticket.order.repository.OrderRepository;
import com.flowticket.order.repository.PaymentRepository;
import com.flowticket.order.repository.RefundRepository;
import com.flowticket.order.sse.OrderSseRegistry;
import com.flowticket.seat.domain.SeatStatus;
import com.flowticket.seat.repository.SeatRepository;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PG에만 남은 환불을 DB에 반영하는 쓰기 전용 협력자(ADR-011).
 *
 * RefundReconciliationService 안에 두면 self-invocation이라 @Transactional이 걸리지 않는다.
 * 분리하면 PG 조회는 트랜잭션 밖에서, 수렴 쓰기만 짧은 트랜잭션에서 돌릴 수 있다.
 */
@Slf4j
@Component
public class RefundConverger {

    /** 정산이 만든 환불 행의 멱등키. 주문당 하나라 재실행해도 두 번 쌓이지 않는다. */
    static String reconcileKey(Long orderId) {
        return "recon-refund-" + orderId;
    }

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final SeatRepository seatRepository;
    private final OrderSseRegistry orderSse;

    public RefundConverger(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                           PaymentRepository paymentRepository, RefundRepository refundRepository,
                           SeatRepository seatRepository, OrderSseRegistry orderSse) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.seatRepository = seatRepository;
        this.orderSse = orderSse;
    }

    /**
     * PAID → CANCELLED → REFUNDED로 수렴시키고 좌석을 푼다. 수렴했으면 true.
     *
     * 금액은 PG가 실제로 취소한 값을 그대로 쓴다. 환불 시점의 정책(RefundPolicy)을 사후에 복원할
     * 수 없으므로 다시 계산하면 PG와 또 어긋난다. 수수료는 결제금액에서 취소금액을 뺀 나머지다.
     *
     * 전이는 RefundService와 같은 순서(조건부 UPDATE → 환불 기록 → 좌석 → 확정)를 따른다.
     * markCancelled가 0행이면 사용자 환불이 방금 이 주문을 가져갔거나 이미 수렴된 것이라 양보한다.
     */
    @Transactional
    public boolean converge(Order order, String pgTid, int canceledAmount) {
        Long orderId = order.getId();
        if (orderRepository.markCancelled(orderId, OrderStatus.PAID) != 1) {
            return false;
        }
        Payment paid = paymentRepository
                .findFirstByOrderIdAndStatusOrderByIdDesc(orderId, PaymentStatus.APPROVED)
                .orElse(null);
        Refund refund = refundRepository.save(Refund.builder()
                .orderId(orderId)
                .paymentId(paid != null ? paid.getId() : null)
                .amount(canceledAmount)
                .fee(order.getAmount() - canceledAmount)
                .reason("정산 복구(PG 취소 확인)")
                .pgRefundTid(pgTid)
                .idempotencyKey(reconcileKey(orderId))
                .build());
        // 좌석 복구(벌크 UPDATE) 전에 확정한다. 컨텍스트가 비워지며 유실되는 것을 막는다(TS-007/010).
        refundRepository.saveAndFlush(refund);

        List<Long> seatIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getSeatId).toList();
        int released = seatRepository.releaseSeats(seatIds, SeatStatus.AVAILABLE, SeatStatus.SOLD);
        if (released != seatIds.size()) {
            // 좌석이 이미 다른 상태면(수동 개입 등) 금전 기록만 맞추고 좌석은 사람이 본다.
            log.warn("[reconcile] 좌석 복구 부분 성공 orderId={} 기대={} 실제={}",
                    orderId, seatIds.size(), released);
        }
        orderRepository.markRefunded(orderId, OrderStatus.CANCELLED);
        orderSse.broadcast(orderId, "order.cancelled", Map.of("orderId", orderId));
        orderSse.broadcast(orderId, "order.refunded", Map.of("orderId", orderId));
        return true;
    }
}
