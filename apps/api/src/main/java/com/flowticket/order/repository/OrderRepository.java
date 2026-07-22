package com.flowticket.order.repository;

import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 멱등 생성: 이 hold로 이미 활성(PENDING/VBANK_WAITING) 주문이 있으면 그것을 재사용. */
    Optional<Order> findFirstByHoldIdAndStatusIn(Long holdId, List<OrderStatus> statuses);

    /** 마이페이지 — 본인 주문 목록(상태 필터, 최신순). 전체(실제 예매)/예정/취소 탭. */
    Page<Order> findByUserIdAndStatusInOrderByIdDesc(Long userId, List<OrderStatus> statuses, Pageable pageable);

    /** 운영 대시보드(S07) — 상태별 주문 수. */
    long countByStatus(OrderStatus status);

    /** 운영 대시보드(S07) — 결제 완료 매출 합계(PAID 주문 금액). */
    @Query("select coalesce(sum(o.amount), 0) from Order o where o.status = com.flowticket.order.domain.OrderStatus.PAID")
    long sumPaidRevenue();

    /**
     * 결제 성공 전이 — 조건부 UPDATE로 원자화(ADR-006). from(PENDING 또는 VBANK_WAITING)인 주문만 PAID로.
     * 반환 1이면 이 요청이 전이의 주인, 0이면 이미 다른 경로가 전이(만료/타 결제)함.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = com.flowticket.order.domain.OrderStatus.PAID, "
            + "o.paidAt = CURRENT_TIMESTAMP where o.id = :id and o.status = :from")
    int markPaid(@Param("id") Long id, @Param("from") OrderStatus from);

    /** 무통장 — PENDING인 주문만 VBANK_WAITING(입금 대기)으로. */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = com.flowticket.order.domain.OrderStatus.VBANK_WAITING "
            + "where o.id = :id and o.status = com.flowticket.order.domain.OrderStatus.PENDING")
    int markVbankWaiting(@Param("id") Long id);

    /** 취소(S06) — PAID인 주문만 CANCELLED로. 조건부 UPDATE로 원자화(ADR-006). 1이면 이 요청이 취소의 주인. */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = com.flowticket.order.domain.OrderStatus.CANCELLED "
            + "where o.id = :id and o.status = :from")
    int markCancelled(@Param("id") Long id, @Param("from") OrderStatus from);

    /** 환불 확정(S06) — CANCELLED인 주문만 REFUNDED로. */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = com.flowticket.order.domain.OrderStatus.REFUNDED "
            + "where o.id = :id and o.status = :from")
    int markRefunded(@Param("id") Long id, @Param("from") OrderStatus from);

    /** 만료 sweep — 결제 제한시각 지난 미완료 주문(PENDING/VBANK_WAITING)을 EXPIRED로. */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = com.flowticket.order.domain.OrderStatus.EXPIRED "
            + "where o.status in :active and o.expiresAt < :now")
    int expireOverdue(@Param("active") List<OrderStatus> active, @Param("now") java.time.LocalDateTime now);
}
