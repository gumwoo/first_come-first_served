package com.flowticket.event.kopis;

import com.flowticket.event.repository.EventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상세 수집 결과를 DB에 반영하는 쓰기 전용 협력자.
 *
 * KopisDetailSyncer 안에 두면 self-invocation이라 @Transactional이 적용되지 않는다.
 * 트랜잭션은 한 건 단위로 좁혀, 외부 호출 동안 DB 커넥션을 물고 있지 않게 한다(TS-021).
 */
@Component
public class KopisDetailWriter {

    private final EventRepository eventRepository;
    private final Clock clock;

    public KopisDetailWriter(EventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /**
     * 한 공연의 상세를 반영한다.
     *
     * @return 반영했으면 true. 그 사이 삭제된 등으로 대상이 없으면 false
     */
    @Transactional
    public boolean apply(Long eventId, KopisEventDetail d) {
        return eventRepository.findById(eventId)
                .map(event -> {
                    // stale 판정(KopisDetailSyncer)과 같은 시계를 쓴다(ADR-018).
                    event.updateDetail(d.runningTime, d.ageLimit, d.priceText,
                            d.cast, d.synopsis, d.schedule, LocalDateTime.now(clock));
                    return true;
                })
                .orElse(false);
    }
}
