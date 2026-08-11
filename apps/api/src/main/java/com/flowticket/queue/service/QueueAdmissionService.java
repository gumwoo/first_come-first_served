package com.flowticket.queue.service;

import com.flowticket.queue.sse.QueueSseRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 대기열 승격(정원 관리). 정원 확인+head pop+카운트 증가를 Redis Lua로 원자화해
 * 동시 실행 시 정원 초과(over-admit)를 막는다. [ADR-002, IMP-004]
 * 입장 후 미진행 토큰은 만료 ZSet sweep으로 슬롯을 회수한다.
 */
@Slf4j
@Service
public class QueueAdmissionService {

    // 여유 슬롯(capacity - admitted)만큼만 wait head를 pop → 원자적. 반환 {member,score,...}
    //
    // ⚠️ 만료 등록(KEYS[3]=admitExp)까지 **이 스크립트 안에서** 한다. 예전에는 pop+INCRBY까지만
    // 원자였고 admitExp 등록은 Java 루프였는데, 그 사이가 벌어져 두 가지가 샜다([[TS-024]]).
    //   ① wait에서도 빠지고 입장 표시도 없는 창 → 상태 조회가 EXPIRED로 떨어진다
    //   ② 그 창에서 Pod가 죽으면 admitcount만 오른 채 admitExp에 없어 **영구 누수**가 된다
    //      (카운트를 줄이는 경로는 RECLAIM/LEAVE뿐이고 둘 다 admitExp를 근거로 움직인다)
    // admitExp는 이벤트 단위 키라 KEYS로 넘길 수 있다 — Lua 안에서 키 이름을 만들지 않으므로
    // Redis Cluster 슬롯 제약(IMP-004 §8)을 새로 만들지 않는다.
    private static final String ADMIT_LUA = """
            local admitted = tonumber(redis.call('GET', KEYS[2]) or '0')
            local free = tonumber(ARGV[1]) - admitted
            if free <= 0 then return {} end
            local popped = redis.call('ZPOPMIN', KEYS[1], free)
            local n = #popped / 2
            if n > 0 then
              redis.call('INCRBY', KEYS[2], n)
              for i = 1, #popped, 2 do
                redis.call('ZADD', KEYS[3], ARGV[2], popped[i])
              end
            end
            return popped
            """;

    // score<=now 만료분을 제거하고 카운트를 그만큼 감소 → 원자적. 반환 만료 토큰 목록
    private static final String RECLAIM_LUA = """
            local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local n = #expired
            if n > 0 then
              redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
              redis.call('DECRBY', KEYS[2], n)
            end
            return expired
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ADMIT_SCRIPT = new DefaultRedisScript<>(ADMIT_LUA, List.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RECLAIM_SCRIPT = new DefaultRedisScript<>(RECLAIM_LUA, List.class);

    private final StringRedisTemplate redis;
    private final QueueSseRegistry sse;
    private final int capacity;
    private final long admitTtl;

    public QueueAdmissionService(StringRedisTemplate redis, QueueSseRegistry sse,
                                 @Value("${queue.capacity:100}") int capacity,
                                 @Value("${queue.admit-ttl:300}") long admitTtl) {
        this.redis = redis;
        this.sse = sse;
        this.capacity = capacity;
        this.admitTtl = admitTtl;
    }

    /** 여유 슬롯만큼 승격. 승격된 토큰 수 반환. */
    public int admit(Long eventId) {
        long expiresAt = Instant.now().getEpochSecond() + admitTtl;
        List<?> popped = redis.execute(ADMIT_SCRIPT,
                List.of(QueueKeys.wait(eventId), QueueKeys.admitCount(eventId), QueueKeys.admitExp(eventId)),
                String.valueOf(capacity), String.valueOf(expiresAt));
        if (popped == null || popped.isEmpty()) {
            return 0;
        }
        // 여기 도착한 시점에 승격은 이미 확정이다(pop+카운트+만료등록이 위에서 원자로 끝났다).
        // 아래 둘은 그 확정을 뒤따르는 부수 작업이라, 중간에 죽어도 슬롯이 새지 않는다.
        //   - admit 키: 상태 조회의 빠른 경로(권위는 admitExp). 없으면 admitExp로 판정된다.
        //   - SSE: 알림. 놓치면 클라이언트 폴백 폴링이 받는다.
        int admitted = 0;
        for (int i = 0; i < popped.size(); i += 2) { // {member,score,...}
            String token = String.valueOf(popped.get(i));
            redis.opsForValue().set(QueueKeys.admit(token), "1", Duration.ofSeconds(admitTtl));
            sse.send(token, "queue.admitted", Map.of("redirect", "/events/" + eventId + "/seats"));
            admitted++;
        }
        return admitted;
    }

    /** 입장창 만료 토큰 회수(슬롯 반환). 회수된 토큰 목록 반환(SSE queue.expired 발행용). */
    public List<String> reclaim(Long eventId) {
        List<?> raw = redis.execute(RECLAIM_SCRIPT,
                List.of(QueueKeys.admitExp(eventId), QueueKeys.admitCount(eventId)),
                String.valueOf(Instant.now().getEpochSecond()));
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> expired = raw.stream().map(String::valueOf).toList();
        for (String token : expired) {
            sse.send(token, "queue.expired");   // 만료 알림
            sse.complete(token);                // 스트림 종료
        }
        return expired;
    }

    /** 승격 워커: 대기 발생 이벤트를 순회하며 회수→승격. */
    // 승격 워커는 ShedLock을 강제하지 않는다(배포 문서 D-3): 정원 확인+pop+증가가 Redis Lua로
    // 원자적이라 다중 Pod 동시 실행이 안전하고, 단일 리더로 묶으면 오히려 처리량 손해. 락=효율,
    // 원자 조건부 연산=정합성(ADR-002/006).
    @Scheduled(fixedRateString = "${queue.admit-interval-ms:1500}")
    public void runOnce() {
        Set<String> events = redis.opsForSet().members(QueueKeys.ACTIVE_EVENTS);
        if (events == null) {
            return;
        }
        for (String e : events) {
            try {
                Long eventId = Long.valueOf(e);
                reclaim(eventId);
                admit(eventId);
                Long waiting = redis.opsForZSet().zCard(QueueKeys.wait(eventId));
                String count = redis.opsForValue().get(QueueKeys.admitCount(eventId));
                long admitted = count == null ? 0 : Long.parseLong(count);
                if ((waiting == null || waiting == 0) && admitted <= 0) {
                    redis.opsForSet().remove(QueueKeys.ACTIVE_EVENTS, e); // 정리
                }
            } catch (Exception ex) {
                log.warn("[queue] 승격 처리 실패 event={}: {}", e, ex.getMessage());
            }
        }
    }
}
