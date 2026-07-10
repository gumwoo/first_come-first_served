package com.flowticket.order.sse;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 주문 실시간 SSE(주문별 다중 구독). 결제 완료/실패/입금확인을 그 주문 구독자에 push.
 * 전송 실패는 제거로 격리(단일 서버 가정). SeatSseRegistry와 동일 패턴.
 */
@Component
public class OrderSseRegistry {

    private final Map<Long, Set<SseEmitter>> byOrder = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public OrderSseRegistry(@Value("${seat.sse-timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public SseEmitter subscribe(Long orderId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        byOrder.computeIfAbsent(orderId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> {
            Set<SseEmitter> set = byOrder.get(orderId);
            if (set != null) {
                set.remove(emitter);
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void broadcast(Long orderId, String event, Object data) {
        Set<SseEmitter> set = byOrder.get(orderId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                set.remove(emitter);
            }
        }
    }
}
