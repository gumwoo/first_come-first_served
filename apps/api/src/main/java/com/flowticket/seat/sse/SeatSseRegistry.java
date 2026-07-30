package com.flowticket.seat.sse;

import com.flowticket.global.sse.SsePubSub;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 좌석맵 실시간 SSE(이벤트별 다중 구독). 선점 만료로 좌석이 풀리면 그 이벤트 구독자 전체에 push.
 * 전송 실패(느린/끊긴 클라이언트)는 제거로 격리.
 *
 * <p>멀티 Pod: {@link #broadcast}는 Redis 채널로 발행만 하고, 실제 전달은 모든 Pod가 구독해
 * {@link #onMessage} → {@link #deliverLocal}로 수행(단일 전달 경로, 자기 자신 포함). pub/sub
 * 미배선(유닛/단일 no-redis)이면 로컬 전달로 폴백.
 */
@Slf4j
@Component
public class SeatSseRegistry implements MessageListener {

    public static final String CHANNEL = "sse:seat";

    private final Map<Long, Set<SseEmitter>> byEvent = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final SsePubSub pubSub;

    public SeatSseRegistry(@Value("${seat.sse-timeout-ms:1800000}") long timeoutMs, SsePubSub pubSub) {
        this.timeoutMs = timeoutMs;
        this.pubSub = pubSub;
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

    /** 이벤트 구독자 전체로 push. 멀티 Pod 팬아웃을 위해 Redis로 발행(미배선 시 로컬 폴백). */
    public void broadcast(Long eventId, String event, Object data) {
        if (pubSub != null) {
            pubSub.publish(CHANNEL, String.valueOf(eventId), event, data);
        } else {
            deliverLocal(eventId, event, data);
        }
    }

    /** 이 Pod의 로컬 구독자에게만 전달(연결 없으면 무시 — 폴링/재조회가 커버). */
    public void deliverLocal(Long eventId, String event, Object data) {
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

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (pubSub == null) {
            return;
        }
        SsePubSub.Envelope env = pubSub.parse(new String(message.getBody(), StandardCharsets.UTF_8));
        if (env != null) {
            deliverLocal(Long.valueOf(env.key()), env.event(), env.data());
        }
    }
}
