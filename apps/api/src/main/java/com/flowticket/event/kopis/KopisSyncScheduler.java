package com.flowticket.event.kopis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 새벽 자동 동기화 진입점.
 *
 * KopisSyncService 안에 두면 this.sync()가 self-invocation이라 @SchedulerLock이 적용되지 않는다.
 * 프록시를 스스로 주입받는 대신 스케줄 진입점을 밖으로 뺐다. 다른 빈을 거치므로 AOP가 정상 적용되고,
 * 락은 여전히 sync() 하나에 걸려 있어 수동 동기화(KopisSyncController)와 같은 문을 지난다.
 *
 * 락을 이 클래스로 옮기지 않은 것은 의도다. 예전에 락이 스케줄 메서드에만 있어 수동 API가 우회했고,
 * 그래서 공통 진입점으로 내렸다(KopisSyncLockIntegrationTest).
 */
@Slf4j
@Component
public class KopisSyncScheduler {

    private final KopisSyncService kopisSyncService;

    public KopisSyncScheduler(KopisSyncService kopisSyncService) {
        this.kopisSyncService = kopisSyncService;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledSync() {
        Integer n = kopisSyncService.sync();
        if (n == null) {
            log.info("[kopis] 다른 인스턴스/수동 실행이 동기화 중: 이번 스케줄은 건너뜀");
            return;
        }
        log.info("[kopis] 스케줄 동기화 {}건", n);
    }
}
