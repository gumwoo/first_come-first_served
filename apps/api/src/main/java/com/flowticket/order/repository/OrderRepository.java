package com.flowticket.order.repository;

import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 멱등 생성: 이 hold로 이미 활성(PENDING/VBANK_WAITING) 주문이 있으면 그것을 재사용. */
    Optional<Order> findFirstByHoldIdAndStatusIn(Long holdId, List<OrderStatus> statuses);

    /**
     * 결제 성공 전이 — 조건부 UPDATE로 원자화(ADR-006). PENDING인 주문만 PAID로.
     * 반환 1이면 이 요청이 전이의 주인, 0이면 이미 다른 경로가 전이(만료/타 결제)함.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Order o set o.status = com.flowticket.order.domain.OrderStatus.PAID, "
            + "o.paidAt = CURRENT_TIMESTAMP where o.id = :id "
            + "and o.status = com.flowticket.order.domain.OrderStatus.PENDING")
    int markPaid(@Param("id") Long id);
}
