package com.flowticket.event.kopis;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * KOPIS 동기화 오케스트레이션: 외부 호출(KopisClient, 트랜잭션 밖) → DB upsert(KopisUpserter, 트랜잭션).
 */
@Slf4j
@Service
public class KopisSyncService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ROWS = 100;

    private final KopisClient kopisClient;
    private final KopisUpserter kopisUpserter;

    public KopisSyncService(KopisClient kopisClient, KopisUpserter kopisUpserter) {
        this.kopisClient = kopisClient;
        this.kopisUpserter = kopisUpserter;
    }

    /** 오늘 ~ +30일 공연 동기화(KOPIS는 최대 31일 조회 제한). 수동 트리거/스케줄 공용. */
    public int sync() {
        LocalDate today = LocalDate.now();
        String st = today.format(YMD);
        String ed = today.plusDays(30).format(YMD); // KOPIS 31일 제한
        List<KopisEvent> items = kopisClient.fetchList(st, ed, 1, ROWS); // 외부 호출
        return kopisUpserter.upsertAll(items);                            // 트랜잭션 DB
    }

    /** 매일 새벽 4시 자동 동기화. */
    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledSync() {
        int n = sync();
        log.info("[kopis] 스케줄 동기화 {}건", n);
    }
}
