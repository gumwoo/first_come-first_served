package com.flowticket.order.event;

import com.flowticket.global.config.KafkaConfig;
import com.flowticket.order.sse.OrderSseRegistry;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * order-events 소비 → 실시간 SSE 전달(S07 Phase 4). Kafka가 이벤트 백본, SSE는 마지막 홉(브라우저 push).
 *
 * <p>아웃박스 릴레이는 at-least-once라 같은 이벤트가 재발행될 수 있다(ADR-010). 그래서 eventId로
 * <b>Redis SETNX 멱등</b>을 건다. SSE 전달은 중복의 업무 영향이 작고 영구적인 처리 감사가 필요 없어
 * 경량 SETNX를 택했다 — 금전·재고를 변경하는 소비자였다면 processed_events 테이블을 비즈니스
 * 트랜잭션과 묶었을 것이다.
 */
@Slf4j
@Component
public class OrderEventConsumer {

    private static final String DEDUP_KEY_PREFIX = "dedup:order-event:";
    /** 재전달 가능 창(릴레이 재시도 + 브로커 장애 + 소비 지연)보다 넉넉하게. */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final OrderSseRegistry orderSse;
    private final StringRedisTemplate redis;

    public OrderEventConsumer(OrderSseRegistry orderSse, StringRedisTemplate redis) {
        this.orderSse = orderSse;
        this.redis = redis;
    }

    @KafkaListener(topics = KafkaConfig.ORDER_EVENTS_TOPIC, groupId = "${spring.kafka.consumer.group-id:flowticket}")
    public void onOrderEvent(OrderEvent event) {
        String key = event.eventId() == null ? null : DEDUP_KEY_PREFIX + event.eventId();
        if (!reserve(key)) {
            return; // 이미 처리한 이벤트(재발행·리밸런스 중복) → 조용히 무시
        }
        try {
            orderSse.broadcast(event.orderId(), event.type(), Map.of("orderId", event.orderId()));
        } catch (RuntimeException e) {
            // 처리 실패 → 예약을 풀어 재시도가 실제로 다시 처리되게 한다.
            // 안 풀면 재시도가 "중복"으로 조용히 통과해 DLQ 적재 경로(ADR-008)가 무력화된다.
            release(key);
            throw e;
        }
    }

    /**
     * 이 이벤트를 처음 처리하는지 예약(SETNX). eventId가 없는 메시지(직접 발행 등)는 멱등 대상 아님.
     *
     * <p>Redis 장애는 <b>fail-open</b>: 멱등 저장소가 죽었다고 소비를 실패시키면 재시도·DLQ로
     * 실시간 알림 전체가 멈춘다. SSE는 중복 피해가 작으므로 경고만 남기고 전달을 진행한다.
     */
    private boolean reserve(String key) {
        if (key == null) {
            return true;
        }
        try {
            return !Boolean.FALSE.equals(redis.opsForValue().setIfAbsent(key, "1", DEDUP_TTL));
        } catch (RuntimeException e) {
            log.warn("[outbox] 멱등 저장소 장애 — 중복을 허용하고 전달 진행: {}", e.toString());
            return true;
        }
    }

    private void release(String key) {
        if (key == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("[outbox] 멱등 예약 해제 실패 key={}: {}", key, e.toString());
        }
    }
}
