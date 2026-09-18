package com.flowticket.event.kopis;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KOPIS 호출 간격 제한기. KOPIS 이용 제한은 IP당 1초 10회이고, 넘기면 키가 막혀 시딩을 못 한다.
 *
 * 목록(fetchList)과 상세(fetchDetail)가 같은 인스턴스를 지나야 합산이 설정값 이하로 유지된다.
 * 프로세스 안의 제어로 IP 총량이 지켜지는 전제는 둘이다: 호출자가 동기화 배치뿐이고, 그 경로에
 * @SchedulerLock(name="kopis-sync")이 걸려 한 번에 한 프로세스만 실행된다.
 *
 * 사용자 요청 경로에서는 부르지 않는다(IMP-018, EventDetailNoExternalCallTest).
 */
@Component
public class KopisRateLimiter {

    private final long minIntervalNanos;
    /** 다음 호출이 허용되는 시각(nanoTime 기준). */
    private long nextAllowedNanos = System.nanoTime();

    public KopisRateLimiter(@Value("${kopis.rate-limit-per-second:5}") double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("kopis.rate-limit-per-second는 0보다 커야 한다");
        }
        this.minIntervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / permitsPerSecond);
    }

    /**
     * 호출 슬롯을 얻을 때까지 현재 스레드를 재운다. 배치 경로 전용이라 블로킹이 타당하다.
     *
     * 인터럽트되면 상태를 복구하고 그대로 던진다. 배치 종료 신호를 삼키면 파드가 제때
     * 내려가지 못한다.
     */
    public void acquire() throws InterruptedException {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            // 오래 쉬었다가 호출하면 nextAllowed가 과거다. 그 경우 밀린 만큼을 몰아 쓰지 않도록
            // 기준점을 now로 당긴다(버스트 허용 안 함).
            long start = Math.max(now, nextAllowedNanos);
            waitNanos = start - now;
            nextAllowedNanos = start + minIntervalNanos;
        }
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }
}
