package com.flowticket.event.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    static final String KEYWORDS = "search:keywords";
    private static final String DEDUP = "view:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofSeconds(60);

    /** 5분마다 ×0.8 → 반감기 ~15분. score<MIN_SCORE는 정리. */
    private static final double DECAY_FACTOR = 0.8;
    private static final double MIN_SCORE = 0.1;

    private final StringRedisTemplate redis;

    public RankingService(StringRedisTemplate redis) {
        this.redis = redis;
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
     * 실시간 ZSET 지수감쇠. ZUNIONSTORE(자기 자신, weight)로 전체 score를 ×DECAY_FACTOR 한 뒤
     * 임계 미만 항목을 제거한다. 누적(TOTAL)은 건드리지 않는다.
     */
    @Scheduled(fixedRateString = "${ranking.decay-rate-ms:300000}")
    public void decay() {
        try {
            redis.opsForZSet().unionAndStore(
                    HOT, Collections.emptyList(), HOT, Aggregate.SUM, Weights.of(DECAY_FACTOR));
            // score < MIN_SCORE 제거(경계값 MIN_SCORE는 유지)
            redis.opsForZSet().removeRangeByScore(HOT, Double.NEGATIVE_INFINITY, MIN_SCORE - 1e-9);
        } catch (Exception e) {
            log.warn("[ranking] 실시간 감쇠 실패: {}", e.getMessage());
        }
    }
}
