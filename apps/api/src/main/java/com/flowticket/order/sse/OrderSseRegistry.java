package com.flowticket.order.sse;

import com.flowticket.global.sse.SsePubSub;
import com.flowticket.global.sse.AfterCommit;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 주문 실시간 SSE(주문별 다중 구독). 결제 완료/실패/입금확인을 그 주문 구독자에 push.
 * 전송 실패는 제거로 격리. SeatSseRegistry와 동일 패턴 — 멀티 Pod는 Redis pub/sub 팬아웃.
 */
@Component
public class OrderSseRegistry implements MessageListener {

    public static final String CHANNEL = "sse:order";

    private final Map<Long, Set<SseEmitter>> byOrder = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final SsePubSub pubSub;

    public OrderSseRegistry(@Value("${seat.sse-timeout-ms:1800000}") long timeoutMs, SsePubSub pubSub) {
        this.timeoutMs = timeoutMs;
        this.pubSub = pubSub;
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

    /**
     * 주문 구독자 전체로 push. 멀티 Pod 팬아웃을 위해 Redis로 발행(미배선 시 로컬 폴백).
     *
     * <p>트랜잭션이 열려 있으면 <b>커밋 후</b>로 미룬다 — 롤백된 상태를 알리지 않기 위해서다.
     * 호출부마다 챙기면 언젠가 빠지므로 팬아웃 입구인 여기서 한 번에 보장한다({@link AfterCommit}).
     */
    public void broadcast(Long orderId, String event, Object data) {
        AfterCommit.run(() -> {
            if (pubSub != null) {
                pubSub.publish(CHANNEL, String.valueOf(orderId), event, data);
            } else {
                deliverLocal(orderId, event, data);
            }
        });
    }

    /** 이 Pod의 로컬 구독자에게만 전달. */
    public void deliverLocal(Long orderId, String event, Object data) {
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
