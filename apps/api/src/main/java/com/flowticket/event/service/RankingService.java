package com.flowticket.event.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 조회수/검색어 랭킹(Redis ZSET). 같은 조회 스트림을 두 가지로 집계한다:
 * - 누적(event:views:total): 감쇠 없음 → "인기 공연 TOP"(장기·안정적)
 * - 실시간(event:views:hot): 주기적 지수감쇠 → "실시간 랭킹"(휘발성)
 * 모든 기록은 best-effort(실패해도 조회 응답을 막지 않음). [domain/event.md]
 */
@Slf4j
@Service
public class RankingService {

    static final String TOTAL = "event:views:total";
    static final String HOT = "event:views:hot";
    /** 마지막 감쇠 시각(epoch ms). 해시 태그를 HOT 키 이름 전체로 둬 Redis Cluster에서도 HOT과 같은 슬롯에 놓인다. */
    static final String HOT_DECAYED_AT = "{" + HOT + "}:decayed-at";
    static final String KEYWORDS = "search:keywords";
    private static final String DEDUP = "view:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofSeconds(60);

    /** 5분마다 ×0.8 → 반감기 ~15분. score<MIN_SCORE는 정리. */
    private static final double DECAY_FACTOR = 0.8;
    private static final double MIN_SCORE = 0.1;
    /** 한 번에 인정하는 경과의 상한(주기 수). 롤백 뒤 재배포·긴 공백 뒤 첫 실행이 랭킹을 통째로 지우지 않게 한다. */
    private static final int MAX_DECAY_PERIODS = 3;

    // 경과 시간만큼만 감쇠한다: score × FACTOR^(경과/주기). 마지막 감쇠 시각을 같은 원자 단위에서 읽고 갱신한다.
    //
    // 파드마다 decay()가 주기적으로 돌고 락(ranking-decay)은 lockAtLeastFor=PT0S라, "실행 횟수 × 0.8"이면
    // 같은 주기에 파드 수만큼 거듭 감쇠된다(반감기가 파드 수에 반비례해 줄어든다 — TS-051). 흐른 시간으로 곱하면
    // 실행이 여러 번이어도 감쇠 총량은 흐른 시간으로만 정해진다 — 0.8^(a/T)·0.8^(b/T) = 0.8^((a+b)/T).
    //
    // 시각은 Java Clock으로 받는다(테스트에서 시계를 돌리기 위해).
    // - 기록이 없으면(첫 실행·키 유실) 한 주기만큼 감쇠한다(기존 동작).
    // - 지금이 마지막 감쇠보다 조금 과거로 보이면(파드 사이 시계 어긋남) 아무것도 하지 않는다.
    // - 기록이 한 주기보다 더 미래면 시계가 앞선 파드가 남긴 값으로 보고, 감쇠 없이 지금으로 다시 쓴다(감쇠가 멈추지 않게).
    // - 경과는 MAX_DECAY_PERIODS 주기까지만 인정한다(긴 공백 뒤 첫 실행에서 랭킹이 통째로 사라지지 않게).
    // KEYS: hot, decayedAt / ARGV: nowMs, periodMs, factor, minScore, maxPeriods. 반환: 1=감쇠함, 0=건너뜀.
    private static final String DECAY_LUA = """
            local now = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            local last = tonumber(redis.call('GET', KEYS[2]))
            local elapsed = period
            if last then
              elapsed = now - last
            end
            if elapsed < -period then
              redis.call('SET', KEYS[2], ARGV[1])
              return 0
            end
            if elapsed <= 0 then
              return 0
            end
            local cap = period * tonumber(ARGV[5])
            if elapsed > cap then
              elapsed = cap
            end
            local weight = tonumber(ARGV[3]) ^ (elapsed / period)
            redis.call('ZUNIONSTORE', KEYS[1], 1, KEYS[1], 'WEIGHTS', string.format('%.17g', weight))
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[4])
            redis.call('SET', KEYS[2], ARGV[1])
            return 1
            """;
    private static final DefaultRedisScript<Long> DECAY_SCRIPT = new DefaultRedisScript<>(DECAY_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final long decayPeriodMs;

    public RankingService(StringRedisTemplate redis, Clock clock,
                          @Value("${ranking.decay-rate-ms:300000}") long decayPeriodMs) {
        if (decayPeriodMs <= 0) {
            // 0이면 Lua에서 0으로 나눠 가중치가 0이 되고 랭킹이 통째로 지워진다.
            throw new IllegalArgumentException("ranking.decay-rate-ms는 양수여야 한다: " + decayPeriodMs);
        }
        this.redis = redis;
        this.clock = clock;
        this.decayPeriodMs = decayPeriodMs;
    }

    /** 상세 진입 1회 기록. 같은 IP 60초 내 재조회는 무시(중복방지). */
    public void recordView(Long eventId, String clientIp) {
        try {
            String dedupKey = DEDUP + eventId + ":" + clientIp;
            Boolean fresh = redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(fresh)) {
                return; // 중복
            }
            String member = String.valueOf(eventId);
            redis.opsForZSet().incrementScore(TOTAL, member, 1);
            redis.opsForZSet().incrementScore(HOT, member, 1);
        } catch (Exception e) {
            log.warn("[ranking] 조회 기록 실패 eventId={}: {}", eventId, e.getMessage());
        }
    }

    /** 검색 실행 1회 기록(인기 검색어). */
    public void recordSearch(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        try {
            redis.opsForZSet().incrementScore(KEYWORDS, keyword.trim(), 1);
        } catch (Exception e) {
            log.warn("[ranking] 검색어 기록 실패 '{}': {}", keyword, e.getMessage());
        }
    }

    /** 누적 조회수 상위 eventId(내림차순). */
    public List<Long> topTotal(int limit) {
        return topIds(TOTAL, limit);
    }

    /** 실시간(감쇠) 조회수 상위 eventId(내림차순). */
    public List<Long> topHot(int limit) {
        return topIds(HOT, limit);
    }

    /** 인기 검색어 상위 N(내림차순). */
    public List<String> topKeywords(int limit) {
        try {
            Set<String> members = redis.opsForZSet().reverseRange(KEYWORDS, 0, limit - 1);
            return members == null ? List.of() : List.copyOf(members);
        } catch (Exception e) {
            log.warn("[ranking] 인기검색어 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Long> topIds(String key, int limit) {
        try {
            Set<String> members = redis.opsForZSet().reverseRange(key, 0, limit - 1);
            if (members == null) {
                return List.of();
            }
            return members.stream().map(Long::valueOf).toList();
        } catch (Exception e) {
            log.warn("[ranking] 랭킹 조회 실패 key={}: {}", key, e.getMessage());
            return List.of();
        }
    }

    /**
     * 실시간 ZSET 지수감쇠. 마지막 감쇠 뒤 흐른 시간만큼 전체 score를 곱하고(DECAY_LUA) 임계 미만 항목을 제거한다.
     * 누적(TOTAL)은 건드리지 않는다. 여러 파드가 같은 주기에 실행해도 결과가 같다(위 주석).
     */
    @Scheduled(fixedRateString = "${ranking.decay-rate-ms:300000}")
    @SchedulerLock(name = "ranking-decay", lockAtMostFor = "PT4M", lockAtLeastFor = "PT0S")
    public void decay() {
        try {
            redis.execute(DECAY_SCRIPT, List.of(HOT, HOT_DECAYED_AT),
                    String.valueOf(clock.millis()), String.valueOf(decayPeriodMs),
                    String.valueOf(DECAY_FACTOR), String.valueOf(MIN_SCORE), String.valueOf(MAX_DECAY_PERIODS));
        } catch (Exception e) {
            log.warn("[ranking] 실시간 감쇠 실패: {}", e.getMessage());
        }
    }
}
