package com.flowticket.order.repository;

import com.flowticket.order.domain.OrderItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    /** 마이페이지 목록 — 여러 주문의 좌석 라인을 한 번에(좌석 수 집계, N+1 방지). */
    List<OrderItem> findByOrderIdIn(List<Long> orderIds);
}
