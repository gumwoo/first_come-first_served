package com.flowticket.order.event;

import com.flowticket.global.config.KafkaConfig;
import com.flowticket.order.sse.OrderSseRegistry;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-events 소비 → 실시간 SSE 전달(S07 Phase 4).
 * Kafka가 이벤트 백본, SSE는 마지막 홉(브라우저 push). 기존 in-memory 브로드캐스트를 대체.
 */
@Component
public class OrderEventConsumer {

    private final OrderSseRegistry orderSse;

    public OrderEventConsumer(OrderSseRegistry orderSse) {
        this.orderSse = orderSse;
    }

    @KafkaListener(topics = KafkaConfig.ORDER_EVENTS_TOPIC, groupId = "flowticket")
    public void onOrderEvent(OrderEvent event) {
        orderSse.broadcast(event.orderId(), event.type(), Map.of("orderId", event.orderId()));
    }
}
