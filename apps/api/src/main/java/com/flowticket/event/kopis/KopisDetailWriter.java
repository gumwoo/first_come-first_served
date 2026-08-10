package com.flowticket.event.kopis;

import com.flowticket.event.repository.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상세 수집 결과를 DB에 반영하는 <b>쓰기 전용</b> 협력자.
 *
 * <p><b>왜 별도 빈인가.</b> {@link KopisDetailSyncer} 안에 이 메서드를 두면 같은 빈에서
 * 자기 자신을 호출하게 되어(self-invocation) 프록시를 거치지 않고, <b>{@code @Transactional}이
 * 조용히 적용되지 않는다.</b> 이 저장소는 같은 함정을 이미 겪었다 — {@code KopisSyncService}가
 * {@code this.sync()}로 부르면 ShedLock이 우회되던 문제다. 그쪽은 self 주입으로 풀었지만,
 * 여기서는 <b>호출자가 비트랜잭션</b>이라 협력자를 분리하는 편이 단순하다.
 *
 * <p>트랜잭션 범위를 한 건으로 좁게 유지하는 것도 의도다. 상세 수집 전체를 한 트랜잭션으로
 * 묶으면 외부 호출이 섞인 채 수 분간 DB 커넥션을 물고 있게 되는데, 커넥션은 이 클러스터에서
 * 이미 병목이다(TS-021 — 파드 × 풀 크기가 RDS 한도를 넘겼다).
 */
@Component
public class KopisDetailWriter {

    private final EventRepository eventRepository;

    public KopisDetailWriter(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
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
                    event.updateDetail(d.runningTime, d.ageLimit, d.priceText,
                            d.cast, d.synopsis, d.schedule);
                    return true;
                })
                .orElse(false);
    }
}
