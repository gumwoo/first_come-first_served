package com.flowticket.queue.sse;

import com.flowticket.global.sse.SsePubSub;
import com.flowticket.global.sse.AfterCommit;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 대기열 SSE 연결 레지스트리(token→emitter). 승격/만료 시 해당 토큰으로 push.
 * 전송 실패(느린/끊긴 클라이언트)는 즉시 제거해 격리한다(ADR-002).
 *
 * <p>멀티 Pod: 승격 워커가 도는 Pod와 SSE 연결을 든 Pod가 다를 수 있어, {@link #send}/{@link #complete}는
 * Redis 채널로 발행하고 모든 Pod가 구독해 로컬 emitter로 전달한다(연결 없는 Pod는 무시). 완료 신호는
 * 예약 이벤트명 {@link #COMPLETE_EVENT}로 라우팅. pub/sub 미배선(유닛)이면 로컬 전달로 폴백.
 */
@Slf4j
@Component
public class QueueSseRegistry implements MessageListener {

    public static final String CHANNEL = "sse:queue";
    /** 스트림 종료 팬아웃용 예약 이벤트명(실제 SSE 이벤트로 나가지 않음). */
    static final String COMPLETE_EVENT = "__complete__";

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final SsePubSub pubSub;

    public QueueSseRegistry(@Value("${queue.token-ttl:1800}") long tokenTtl, SsePubSub pubSub) {
        this.timeoutMs = tokenTtl * 1000L;
        this.pubSub = pubSub;
    }

    /**
     * 토큰용 SSE 스트림 생성·등록. 완료/타임아웃/에러 시 자동 정리.
     *
     * <p><b>연결 직후 코멘트 프레임을 한 번 보낸다.</b> 이게 없으면 응답이 커밋되지 않아
     * 브라우저의 {@code EventSource}가 OPEN으로 전이하지 않고 <b>{@code onopen}이 불리지
     * 않는다.</b> 프론트는 재연결 시 {@code onopen}에서 상태를 다시 읽어 놓친 승격 알림을
     * 복구하는데([[ADR-015]] ①), 그 복구가 통째로 발동하지 못한다.
     *
     * <p>2026-08-16 E2E trace로 관측했다 — 재연결 요청은 성립했는데 그 뒤 상태 조회가
     * 한 건도 없었다. 승격 이벤트를 놓친 사용자는 폴링이 없으면 계속 대기 화면에 남았다.
     *
     * <p>코멘트({@code :}로 시작)를 쓰는 이유는 <b>프로토콜 표면을 늘리지 않기 위해서다</b> —
     * {@code EventSource}가 무시하므로 프론트에 리스너를 추가할 필요가 없다.
     *
     * <p>⚠️ 이것은 <b>연결 성립을 위한 1회 전송</b>이고, 오래 유휴한 연결이 프록시(ALB 등)에
     * 끊기는 것을 막는 <b>주기적 하트비트와는 다른 문제</b>다. 후자는 idle timeout 실제값을
     * 확인한 뒤 별도로 정한다(ADR-015 ②).
     */
    public SseEmitter subscribe(String token) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitters.put(token, emitter);
        emitter.onCompletion(() -> emitters.remove(token));
        emitter.onTimeout(() -> {
            emitters.remove(token);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(token));
        try {
            emitter.send(SseEmitter.event().comment("open"));
        } catch (Exception e) {
            // 구독 시작도 못 한 연결이다 — 맵에 남기면 이후 전송이 계속 실패한다.
            emitters.remove(token);
            log.debug("SSE 초기 프레임 전송 실패 token={}", token, e);
        }
        return emitter;
    }

    /**
     * 해당 토큰 연결로 이벤트 push. 멀티 Pod 팬아웃을 위해 Redis로 발행(미배선 시 로컬 폴백).
     *
     * <p>트랜잭션이 열려 있으면 <b>커밋 후</b>로 미룬다 — 롤백된 상태를 알리지 않기 위해서다.
     * 호출부마다 챙기면 언젠가 빠지므로 팬아웃 입구인 여기서 한 번에 보장한다({@link AfterCommit}).
     */
    public void send(String token, String event, Object data) {
        AfterCommit.run(() -> {
            if (pubSub != null) {
                pubSub.publish(CHANNEL, token, event, data);
            } else {
                deliverLocal(token, event, data);
            }
        });
    }

    /** 이 Pod의 로컬 연결로만 전달(연결 없으면 무시 — 폴링 폴백이 커버). */
    public void deliverLocal(String token, String event, Object data) {
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

    /** 완료 신호 후 정리(만료 등 종료 이벤트 뒤). 멀티 Pod에선 연결 보유 Pod에서 실행되도록 팬아웃. */
    public void complete(String token) {
        AfterCommit.run(() -> {   // send와 같은 이유로 커밋 후(트랜잭션 없으면 즉시)
            if (pubSub != null) {
                pubSub.publish(CHANNEL, token, COMPLETE_EVENT, Map.of());
            } else {
                completeLocal(token);
            }
        });
    }

    private void completeLocal(String token) {
        SseEmitter emitter = emitters.remove(token);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 이미 닫힘
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (pubSub == null) {
            return;
        }
        SsePubSub.Envelope env = pubSub.parse(new String(message.getBody(), StandardCharsets.UTF_8));
        if (env == null) {
            return;
        }
        if (COMPLETE_EVENT.equals(env.event())) {
            completeLocal(env.key());
        } else {
            deliverLocal(env.key(), env.event(), env.data());
        }
    }

    /** 편의: 이벤트 데이터 없음. */
    public void send(String token, String event) {
        send(token, event, Map.of());
    }
}
