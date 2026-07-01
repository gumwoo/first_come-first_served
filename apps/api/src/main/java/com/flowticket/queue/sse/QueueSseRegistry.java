package com.flowticket.queue.sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 SSE 연결 레지스트리(token→emitter). 승격/만료 시 해당 토큰으로 push.
 * 전송 실패(느린/끊긴 클라이언트)는 즉시 제거해 격리한다(단일 서버 가정, ADR-002).
 */
@Slf4j
@Component
public class QueueSseRegistry {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public QueueSseRegistry(@Value("${queue.token-ttl:1800}") long tokenTtl) {
        this.timeoutMs = tokenTtl * 1000L;
    }

    /** 토큰용 SSE 스트림 생성·등록. 완료/타임아웃/에러 시 자동 정리. */
    public SseEmitter subscribe(String token) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitters.put(token, emitter);
        emitter.onCompletion(() -> emitters.remove(token));
        emitter.onTimeout(() -> {
            emitters.remove(token);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(token));
        return emitter;
    }

    /** 해당 토큰 연결로 이벤트 push(연결 없으면 무시 — 폴링 폴백이 커버). */
    public void send(String token, String event, Object data) {
        SseEmitter emitter = emitters.get(token);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            emitters.remove(token); // 전송 실패 → 격리
        }
    }

    /** 완료 신호 후 정리(만료 등 종료 이벤트 뒤). */
    public void complete(String token) {
        SseEmitter emitter = emitters.remove(token);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 이미 닫힘
            }
        }
    }

    /** 편의: 이벤트 데이터 없음. */
    public void send(String token, String event) {
        send(token, event, Map.of());
    }
}
