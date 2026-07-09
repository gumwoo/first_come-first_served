package com.flowticket.order.repository;

import com.flowticket.order.domain.Order;
import com.flowticket.order.domain.OrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 멱등 생성: 이 hold로 이미 활성(PENDING/VBANK_WAITING) 주문이 있으면 그것을 재사용. */
    Optional<Order> findFirstByHoldIdAndStatusIn(Long holdId, List<OrderStatus> statuses);
}
