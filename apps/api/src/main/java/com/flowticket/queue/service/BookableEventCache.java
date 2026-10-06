package com.flowticket.queue.service;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import com.flowticket.event.repository.EventRepository;
import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 대기열 진입 경로의 판매 상태 조회(파드 메모리, 짧은 TTL).
 *
 * 진입 요청마다 공연을 DB에서 읽었다(PK 단건). 진입은 버스트로 몰리는 경로라 그 조회가 그대로 DB 부하가 된다 —
 * 진입 1건당 DB 커넥션 획득이 1.01회였고(측정 세션 20261005-1440), JFR 표본(참고용 — JFR 부담 포함)에서 이 조회가 api CPU의 7.6~11.9%였다.
 * 2,000/s에서 Hikari 대기가 한 번 보였다(파드 1개·표본 1개, 3,000/s에서는 0 — 병목으로 확인한 것은 아니다).
 * 같은 공연의 상태는 버스트 동안 거의 바뀌지 않으므로 짧게 기억한다.
 *
 * 대가: 관리자가 판매를 멈추거나 재개하면 TTL 동안 파드마다 이전 상태로 판정할 수 있다(진입 토큰 발급만 해당 —
 * 좌석 선점·주문은 이 캐시를 쓰지 않는다). 그래서 TTL은 짧게 두고 기본은 끈다(0 = 매번 DB, 지금까지와 같다).
 * 없는 공연(NOT_FOUND)은 기억하지 않는다 — 임의의 id로 맵이 커지지 않게. 맵 크기는 실재하는 공연 수로 묶인다.
 */
@Component
public class BookableEventCache {

    private final EventRepository eventRepository;
    private final Clock clock;
    /** 0이면 캐시를 쓰지 않는다(기본). */
    private final long ttlMs;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(EventStatus status, long expiresAtMs) {
    }

    public BookableEventCache(EventRepository eventRepository, Clock clock,
                              @Value("${queue.bookable-cache-ttl-ms:0}") long ttlMs) {
        this.eventRepository = eventRepository;
        this.clock = clock;
        this.ttlMs = ttlMs;
    }

    /** 공연의 판매 상태. 없으면 NOT_FOUND. */
    public EventStatus status(Long eventId) {
        if (ttlMs <= 0) {
            return load(eventId);
        }
        long now = clock.millis();
        Entry hit = entries.get(eventId);
        if (hit != null && hit.expiresAtMs() > now) {
            return hit.status();
        }
        EventStatus fresh = load(eventId);
        entries.put(eventId, new Entry(fresh, now + ttlMs));
        return fresh;
    }

    private EventStatus load(Long eventId) {
        return eventRepository.findById(eventId)
                .map(Event::getStatus)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
