package com.flowticket.event.kopis;

import com.flowticket.event.dto.KopisSyncStatusResponse;
import com.flowticket.seat.service.SeatSeeder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KOPIS 동기화 오케스트레이션: 외부 호출(KopisClient, 트랜잭션 밖) → DB upsert(KopisUpserter, 트랜잭션).
 * KOPIS 31일 조회 제한을 우회하려 기간을 31일 청크로 나눠 페이지 끝까지 수집한다.
 */
@Slf4j
@Service
public class KopisSyncService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int CHUNK_DAYS = 31; // KOPIS 단일 조회 최대 기간

    private final KopisClient kopisClient;
    private final KopisUpserter kopisUpserter;
    private final KopisDetailSyncer detailSyncer;
    private final SeatSeeder seatSeeder;
    private final int syncDays;
    private final int rows;
    private final int maxPages;
    private final ObjectProvider<KopisSyncService> self; // 락 프록시 경유 self-호출용

    public KopisSyncService(KopisClient kopisClient, KopisUpserter kopisUpserter,
                            KopisDetailSyncer detailSyncer, SeatSeeder seatSeeder,
                            @Value("${kopis.sync.days:90}") int syncDays,
                            @Value("${kopis.sync.rows:100}") int rows,
                            @Value("${kopis.sync.max-pages:10}") int maxPages,
                            ObjectProvider<KopisSyncService> self) {
        this.kopisClient = kopisClient;
        this.kopisUpserter = kopisUpserter;
        this.detailSyncer = detailSyncer;
        this.seatSeeder = seatSeeder;
        this.syncDays = syncDays;
        this.rows = rows;
        this.maxPages = maxPages;
        this.self = self;
    }

    /**
     * 상세 동기화 진행 상황. 기동 스크립트가 "초기 수집이 끝났는가"를 판단하는 데 쓴다.
     *
     * <p>왜 필요한가: 상세는 회차당 상한(300)만큼만 처리되므로 갓 만든 클러스터에서는 여러 번
     * 돌려야 한다. 그런데 {@code detailSyncedAt}이 어떤 API로도 나가지 않아, 밖에서는
     * 개별 필드({@code runningTime})가 비었는지로 대신 셀 수밖에 없었다 — <b>그 대리값은 틀리다.</b>
     * {@code Event.updateDetail()}은 그 필드가 없어도 {@code detailSyncedAt}을 찍기 때문이다.
     */
    @Transactional(readOnly = true)
    public KopisSyncStatusResponse status() {
        return new KopisSyncStatusResponse(
                detailSyncer.totalKopisEvents(),
                detailSyncer.missingDetailCount());
    }

    /**
     * 오늘 ~ +syncDays 공연 동기화. 31일 청크로 분할·페이지 끝까지 수집 후 upsert(멱등).
     *
     * <p><b>락은 이 공통 진입점에 건다.</b> 예전엔 {@code scheduledSync()}에만 붙어 있어 수동 API가
     * 이 메서드를 직접 호출하면 락을 우회했고, 스케줄 동기화와 수동 동기화가 겹칠 수 있었다
     * (KOPIS 중복 호출·동시 upsert 경합). 이제 어느 경로든 같은 락을 통과한다.
     *
     * @return upsert 처리 건수. 락을 잡지 못해 <b>실행되지 않으면 null</b>(ShedLock이 호출을 건너뜀) —
     *         호출자는 이를 "이미 동기화 중"으로 구분해 처리해야 한다(그래서 primitive int가 아니다).
     */
    @SchedulerLock(name = "kopis-sync", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public Integer sync() {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(syncDays);
        List<KopisEvent> all = new ArrayList<>();
        for (LocalDate st = today; st.isBefore(end); st = st.plusDays(CHUNK_DAYS)) {
            LocalDate ed = st.plusDays(CHUNK_DAYS - 1).isAfter(end) ? end : st.plusDays(CHUNK_DAYS - 1);
            all.addAll(kopisClient.fetchListAll(st.format(YMD), ed.format(YMD), rows, maxPages)); // 외부 호출
        }
        int upserted = kopisUpserter.upsertAll(all); // 트랜잭션 DB (kopis_id 멱등)
        try {
            // 상세는 목록 upsert 뒤에 채운다 — 신규 공연이 먼저 행으로 있어야 대상이 된다.
            // best-effort: 상세 수집이 실패해도 목록 동기화 결과를 버리지 않는다.
            // 남은 건은 detailSyncedAt이 NULL로 남아 다음 회차가 이어받는다.
            detailSyncer.syncMissingDetails();
        } catch (Exception e) {
            log.warn("[kopis] 상세 동기화 실패: {}", e.getMessage());
        }
        try {
            int seeded = seatSeeder.seedSellable(); // 판매 가능 공연에 좌석 자동 생성(멱등, best-effort)
            if (seeded > 0) {
                log.info("[seat] 자동 좌석 시딩 {}건", seeded);
            }
        } catch (Exception e) {
            log.warn("[seat] 자동 좌석 시딩 실패: {}", e.getMessage()); // 시딩 실패가 동기화를 막지 않음
        }
        return upserted;
    }

    /**
     * 매일 새벽 4시 자동 동기화. 락은 {@link #sync()}에 있으므로 <b>프록시를 거쳐</b> 호출한다 —
     * {@code this.sync()}로 부르면 self-invocation이라 AOP가 적용되지 않아 락을 우회한다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledSync() {
        Integer n = self.getObject().sync();
        if (n == null) {
            log.info("[kopis] 다른 인스턴스/수동 실행이 동기화 중 — 이번 스케줄은 건너뜀");
            return;
        }
        log.info("[kopis] 스케줄 동기화 {}건", n);
    }
}
