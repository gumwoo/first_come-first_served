package com.flowticket.outbox.service;

import com.flowticket.order.sse.OrderSseRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위반 fixture: 릴레이가 broadcast()로 발행한다. broadcast는 커밋 뒤로 미루고(AfterCommit) 실패를 삼키므로,
 * 트랜잭션 안에서 부르면 PUBLISHED 마킹이 먼저 커밋되고 Redis 장애 때 이벤트가 사라진다(at-most-once).
 */
@Service
public class OutboxRelay {

    private final OrderSseRegistry orderSse;

    public OutboxRelay(OrderSseRegistry orderSse) {
        this.orderSse = orderSse;
    }

    @Transactional
    public void publishPending() {
        orderSse.broadcast(1L, "order.paid", null);
    }
}
