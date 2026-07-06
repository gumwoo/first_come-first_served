package com.flowticket.event.kopis;

import com.flowticket.seat.service.SeatSeeder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    private final SeatSeeder seatSeeder;
    private final int syncDays;
    private final int rows;
    private final int maxPages;

    public KopisSyncService(KopisClient kopisClient, KopisUpserter kopisUpserter, SeatSeeder seatSeeder,
                            @Value("${kopis.sync.days:90}") int syncDays,
                            @Value("${kopis.sync.rows:100}") int rows,
                            @Value("${kopis.sync.max-pages:10}") int maxPages) {
        this.kopisClient = kopisClient;
        this.kopisUpserter = kopisUpserter;
        this.seatSeeder = seatSeeder;
        this.syncDays = syncDays;
        this.rows = rows;
        this.maxPages = maxPages;
    }

    /** 오늘 ~ +syncDays 공연 동기화. 31일 청크로 분할·페이지 끝까지 수집 후 upsert(멱등). */
    public int sync() {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(syncDays);
        List<KopisEvent> all = new ArrayList<>();
        for (LocalDate st = today; st.isBefore(end); st = st.plusDays(CHUNK_DAYS)) {
            LocalDate ed = st.plusDays(CHUNK_DAYS - 1).isAfter(end) ? end : st.plusDays(CHUNK_DAYS - 1);
            all.addAll(kopisClient.fetchListAll(st.format(YMD), ed.format(YMD), rows, maxPages)); // 외부 호출
        }
        int upserted = kopisUpserter.upsertAll(all); // 트랜잭션 DB (kopis_id 멱등)
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

    /** 매일 새벽 4시 자동 동기화. */
    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledSync() {
        int n = sync();
        log.info("[kopis] 스케줄 동기화 {}건", n);
    }
}
