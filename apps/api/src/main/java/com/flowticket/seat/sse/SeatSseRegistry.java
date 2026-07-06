package com.flowticket.seat.sse;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 좌석맵 실시간 SSE(이벤트별 다중 구독). 선점 만료로 좌석이 풀리면 그 이벤트 구독자 전체에 push.
 * 전송 실패(느린/끊긴 클라이언트)는 제거로 격리(단일 서버 가정).
 */
@Slf4j
@Component
public class SeatSseRegistry {

    private final Map<Long, Set<SseEmitter>> byEvent = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public SeatSseRegistry(@Value("${seat.sse-timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public SseEmitter subscribe(Long eventId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        byEvent.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> {
            Set<SseEmitter> set = byEvent.get(eventId);
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

    /** 이벤트 구독자 전체로 push(연결 없으면 무시 — 폴링/재조회가 커버). */
    public void broadcast(Long eventId, String event, Object data) {
        Set<SseEmitter> set = byEvent.get(eventId);
        if (set == null) {
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (Exception e) {
                set.remove(emitter); // 전송 실패 → 격리
            }
        }
    }
}
