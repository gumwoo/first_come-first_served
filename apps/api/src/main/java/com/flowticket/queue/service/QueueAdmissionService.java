package com.flowticket.queue.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private static final String ADMIT_LUA = """
            local admitted = tonumber(redis.call('GET', KEYS[2]) or '0')
            local free = tonumber(ARGV[1]) - admitted
            if free <= 0 then return {} end
            local popped = redis.call('ZPOPMIN', KEYS[1], free)
            local n = #popped / 2
            if n > 0 then redis.call('INCRBY', KEYS[2], n) end
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
    private final int capacity;
    private final long admitTtl;

    public QueueAdmissionService(StringRedisTemplate redis,
                                 @Value("${queue.capacity:100}") int capacity,
                                 @Value("${queue.admit-ttl:300}") long admitTtl) {
        this.redis = redis;
        this.capacity = capacity;
        this.admitTtl = admitTtl;
    }

    /** 여유 슬롯만큼 승격. 승격된 토큰 수 반환. */
    public int admit(Long eventId) {
        List<?> popped = redis.execute(ADMIT_SCRIPT,
                List.of(QueueKeys.wait(eventId), QueueKeys.admitCount(eventId)),
                String.valueOf(capacity));
        if (popped == null || popped.isEmpty()) {
            return 0;
        }
        long expiresAt = Instant.now().getEpochSecond() + admitTtl;
        int admitted = 0;
        for (int i = 0; i < popped.size(); i += 2) { // {member,score,...}
            String token = String.valueOf(popped.get(i));
            redis.opsForValue().set(QueueKeys.admit(token), "1", Duration.ofSeconds(admitTtl));
            redis.opsForZSet().add(QueueKeys.admitExp(eventId), token, expiresAt);
            admitted++;
        }
        return admitted;
    }

    /** 입장창 만료 토큰 회수(슬롯 반환). 회수된 토큰 목록 반환(SSE queue.expired 발행용). */
    public List<String> reclaim(Long eventId) {
        List<?> expired = redis.execute(RECLAIM_SCRIPT,
                List.of(QueueKeys.admitExp(eventId), QueueKeys.admitCount(eventId)),
                String.valueOf(Instant.now().getEpochSecond()));
        return expired == null ? List.of() : expired.stream().map(String::valueOf).toList();
    }

    /** 승격 워커: 대기 발생 이벤트를 순회하며 회수→승격. */
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
