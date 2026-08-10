package com.flowticket.event.kopis;

import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * KOPIS 상세를 <b>미리 받아 DB에 채운다</b>. 사용자 요청 경로에서 외부 호출을 없애기 위한 짝이다.
 *
 * <p><b>왜 배치가 미리 채우나.</b> 예전에는 {@code GET /events/{id}}마다 외부를 호출했다.
 * 그러면 외부 호출량이 우리 트래픽의 함수가 된다 — 부하 시 초당 70회가 나가 KOPIS 이용 제한
 * (IP당 1초 10회)을 약 7배 초과했다. 배치로 옮기면 호출량이 <b>하루 1회 고정</b>이 되어
 * 트래픽과 분리되고, KOPIS가 죽어도 이미 받아둔 값으로 화면이 유지된다.
 *
 * <p><b>첫 요청 때 채우는 방식(lazy cache)을 고르지 않은 이유.</b> 그러면 공연 수(1,446건)만큼
 * "첫 요청"이 있고, 오픈 직후 트래픽이 몰리면 수십 건이 동시에 밖으로 나간다. 트래픽 비례
 * 구간이 그대로 남아 제한을 다시 넘길 수 있다.
 */
@Slf4j
@Component
public class KopisDetailSyncer {

    private final EventRepository eventRepository;
    private final KopisClient kopisClient;
    private final KopisDetailWriter writer;
    private final int batchLimit;

    public KopisDetailSyncer(EventRepository eventRepository, KopisClient kopisClient,
                             KopisDetailWriter writer,
                             @Value("${kopis.sync.detail-batch-limit:300}") int batchLimit) {
        this.eventRepository = eventRepository;
        this.kopisClient = kopisClient;
        this.writer = writer;
        this.batchLimit = batchLimit;
    }

    /**
     * 상세가 비어 있는 공연을 채운다. 한 번에 {@code detail-batch-limit}건까지만 처리한다.
     *
     * <p><b>왜 한 번에 다 하지 않나.</b> 레이트 리밋(기본 5회/초) 때문에 1,446건이면 약 5분이
     * 걸리고, 그동안 ShedLock의 {@code lockAtMostFor=PT10M}을 잡아먹는다. 나눠서 처리하면
     * 한 회차가 짧고, 남은 건은 다음 동기화가 이어받는다 — {@code detailSyncedAt}이 NULL인
     * 것만 고르므로 <b>이어받기가 자동</b>이다.
     *
     * @return 이번 회차에 채운 건수
     */
    public int syncMissingDetails() {
        List<Long> ids = eventRepository.findIdsNeedingDetail(PageRequest.ofSize(batchLimit));
        if (ids.isEmpty()) {
            return 0;
        }
        int filled = 0;
        for (Long id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[kopis] 상세 동기화 중단(인터럽트) — {}건 처리 후", filled);
                break;
            }
            if (syncOne(id)) {
                filled++;
            }
        }
        log.info("[kopis] 상세 동기화 {}건 채움(대상 {}건)", filled, ids.size());
        return filled;
    }

    /**
     * 한 건을 채운다. <b>건별로 트랜잭션을 연다</b> — 전체를 한 트랜잭션으로 묶으면 외부 호출이
     * 섞인 채 수 분간 커넥션을 물고 있게 된다. 커넥션은 이 클러스터에서 이미 병목이다(TS-021).
     *
     * <p>외부 호출은 트랜잭션 <b>밖</b>에서 끝내고, 저장만 짧게 감싼다.
     *
     * @return 실제로 채웠으면 true. 외부 실패·대상 없음이면 false(다음 회차가 다시 시도)
     */
    private boolean syncOne(Long id) {
        String kopisId = eventRepository.findById(id).map(Event::getKopisId).orElse(null);
        if (kopisId == null) {
            return false;
        }
        // 외부 호출 — 트랜잭션 밖. 실패하면 detailSyncedAt을 남기지 않아 다음에 다시 대상이 된다.
        KopisEventDetail detail = kopisClient.fetchDetail(kopisId).orElse(null);
        if (detail == null) {
            return false;
        }
        // 쓰기는 별도 빈에 맡긴다 — 같은 빈에서 부르면 프록시를 거치지 않아 @Transactional이
        // 조용히 적용되지 않는다(KopisDetailWriter 주석 참조).
        return writer.apply(id, detail);
    }
}
