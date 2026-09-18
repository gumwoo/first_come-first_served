package com.flowticket.event.kopis;

import com.flowticket.event.domain.Event;
import com.flowticket.event.repository.EventRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * KOPIS 상세를 미리 받아 DB에 채운다. 사용자 요청 경로에서 외부 호출을 없애기 위한 짝이다.
 *
 * 왜 배치가 미리 채우나. 요청마다 외부를 호출하면 외부 호출량이 트래픽의 함수가 되어
 * KOPIS 이용 제한(IP당 1초 10회)을 넘긴다. 배치로 옮기면 호출량이 사용자 트래픽과 분리되어
 * 배치 실행량(회차당 상한 × 실행 주기)으로 통제되고, KOPIS가 죽어도 이미 받아둔 값으로
 * 화면이 유지된다.
 *
 * 첫 요청 때 채우는 방식(lazy cache)을 고르지 않은 이유. 그러면 공연 수(1,446건)만큼
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
    private final int refreshAfterDays;

    public KopisDetailSyncer(EventRepository eventRepository, KopisClient kopisClient,
                             KopisDetailWriter writer,
                             @Value("${kopis.sync.detail-batch-limit:300}") int batchLimit,
                             @Value("${kopis.sync.detail-refresh-after-days:7}") int refreshAfterDays) {
        this.eventRepository = eventRepository;
        this.kopisClient = kopisClient;
        this.writer = writer;
        this.batchLimit = batchLimit;
        this.refreshAfterDays = refreshAfterDays;
    }

    /**
     * 상세를 한 번도 못 받은 공연 수. 0이면 초기 수집이 끝났다는 뜻이다.
     *
     * syncMissingDetails()의 대상 조건과 일부러 다르다. 그쪽은 "오래된 것"도 넣어
     * 순환 갱신하므로 0이 될 수 없다. 이 값은 "초기 수집 완료"를 묻는다.
     */
    public long missingDetailCount() {
        return eventRepository.countByKopisIdIsNotNullAndDetailSyncedAtIsNull();
    }

    /** KOPIS에서 온 공연 수(분모). */
    public long totalKopisEvents() {
        return eventRepository.countByKopisIdIsNotNull();
    }

    /**
     * 상세를 채우고 갱신한다. 미수집(NULL)과 오래된 것을 함께 대상으로 삼되, 한 번에
     * detail-batch-limit건까지만 처리한다. 레이트 리밋 때문에 전량이면 ShedLock
     * lockAtMostFor를 잡아먹으므로 나눠서 이어받는다. 순환 주기와 갱신 주기의 관계는 TS-033.
     *
     * @return 이번 회차에 채운 건수
     */
    public int syncMissingDetails() {
        LocalDateTime staleBefore = LocalDateTime.now().minusDays(refreshAfterDays);
        List<Long> ids = eventRepository.findIdsNeedingDetail(staleBefore, PageRequest.ofSize(batchLimit));
        if (ids.isEmpty()) {
            return 0;
        }
        int filled = 0;
        for (Long id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[kopis] 상세 동기화 중단(인터럽트): {}건 처리 후", filled);
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
     * 한 건을 채운다. 건별로 트랜잭션을 연다. 전체를 한 트랜잭션으로 묶으면 외부 호출이
     * 섞인 채 수 분간 커넥션을 물고 있게 된다. 커넥션은 이 클러스터에서 이미 병목이다(TS-021).
     *
     * 외부 호출은 트랜잭션 밖에서 끝내고, 저장만 짧게 감싼다.
     *
     * @return 실제로 채웠으면 true. 외부 실패·대상 없음이면 false(다음 회차가 다시 시도)
     */
    private boolean syncOne(Long id) {
        String kopisId = eventRepository.findById(id).map(Event::getKopisId).orElse(null);
        if (kopisId == null) {
            return false;
        }
        // 외부 호출: 트랜잭션 밖. 실패하면 detailSyncedAt을 남기지 않아 다음에 다시 대상이 된다.
        KopisEventDetail detail = kopisClient.fetchDetail(kopisId).orElse(null);
        if (detail == null) {
            return false;
        }
        // 쓰기는 별도 빈에 맡긴다. 같은 빈에서 부르면 프록시를 거치지 않아 @Transactional이
        // 적용되지 않는다(KopisDetailWriter 주석 참조).
        return writer.apply(id, detail);
    }
}
