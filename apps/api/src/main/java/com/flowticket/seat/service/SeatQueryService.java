package com.flowticket.seat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.seat.dto.SeatMapResponse;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석맵 조회. 선점·해제(SeatService)와 일관성 모델이 다르다.
 *
 * 조회는 짧은 TTL 캐시를 허용한다. 그동안 선점된 좌석이 AVAILABLE로 보일 수 있고(IMP-020), 그것을
 * 감수하는 대신 DB 커넥션을 아낀다. 반대로 선점은 조건부 UPDATE로 한 좌석도 겹치지 않게 한다.
 * 같은 클래스에 두면 이 차이가 메서드마다 붙은 전파 속성에만 남는다.
 */
@Slf4j
@Service
public class SeatQueryService {

    private final SeatMapLoader loader;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    /** 0이면 캐시를 쓰지 않는다(기본). 실험 스위치(IMP-020). */
    private final long mapCacheTtlMs;

    public SeatQueryService(SeatMapLoader loader, StringRedisTemplate redis, ObjectMapper objectMapper,
                            @Value("${seat.map-cache-ttl-ms:0}") long mapCacheTtlMs) {
        this.loader = loader;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.mapCacheTtlMs = mapCacheTtlMs;
    }

    /**
     * 좌석맵: 등급 요약(가격·잔여) + 개별 좌석.
     *
     * NOT_SUPPORTED로 트랜잭션 밖에서 돈다. 캐시 히트면 DB 커넥션을 아예 쥐지 않고, miss일 때만
     * loader가 읽기 전용 트랜잭션을 연다. 호출자가 트랜잭션 안이면 그것을 suspend하므로,
     * 트랜잭션 안에서 이 메서드를 부르는 코드가 생기면 다시 봐야 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SeatMapResponse getSeats(Long eventId) {
        if (mapCacheTtlMs <= 0) {
            return loader.load(eventId);
        }
        String key = "cache:seatmap:" + eventId;
        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) {
                return objectMapper.readValue(hit, SeatMapResponse.class);
            }
        } catch (Exception e) {
            log.warn("[seat] 좌석맵 캐시 읽기 실패 event={}: DB로 폴백: {}", eventId, e.getMessage());
        }
        SeatMapResponse fresh = loader.load(eventId);
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(fresh),
                    Duration.ofMillis(mapCacheTtlMs));
        } catch (Exception e) {
            log.warn("[seat] 좌석맵 캐시 쓰기 실패 event={}: {}", eventId, e.getMessage());
        }
        return fresh;
    }
}
