package com.flowticket.event.kopis;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * KOPIS 호출 간격 제한기. <b>KOPIS 이용 제한은 IP당 1초 10회이고, 넘기면 서비스가 중지된다.</b>
 *
 * <p>단순히 느리게 하는 장치가 아니라 <b>남의 서비스에 대한 약속</b>이다. 초과하면 우리 키가
 * 막혀 시딩 자체를 못 하게 된다.
 *
 * <p><b>이 제한기가 보장하는 범위.</b> <b>동기화 배치 경로에만</b> 쓴다. 그 경로는
 * {@code @SchedulerLock(name="kopis-sync")}이 걸려 있어 파드가 몇 개든 <b>한 번에 한
 * 프로세스만</b> 실행하므로, 프로세스 안에서 간격을 지키면 <b>동기화가 만드는 호출량</b>이
 * 설정값 이하로 유지된다.
 *
 * <p><b>IP 전체 총량은 아직 보장하지 못한다.</b> ShedLock이 보장하는 것은 "동기화 프로세스가
 * 하나"이지 "이 IP에서 KOPIS로 나가는 모든 호출이 이 제한기를 통과한다"가 아니다. 같은 NAT IP로
 * 사용자 요청 경로({@code fetchDetail})가 <b>제한 없이</b> 나가고 있어, 둘을 합치면 IP 제한
 * (1초 10회)을 넘길 수 있다. 실제로 부하 시 사용자 경로만으로 초당 70회 수준이 나갔다.
 *
 * <p><b>그러면 사용자 경로에도 걸면 되지 않나 — 안 된다.</b> 파드마다 별도 인스턴스라 어차피
 * IP 총량을 못 지킬뿐더러, 더 나쁘게는 <b>요청 스레드를 여기서 재우게 된다.</b> 외부 API가
 * 느려질 때 톰캣 스레드가 묶여 API 전체가 멎는 실패를 이미 겪었다(KopisClientConfig 주석 참조)
 * — 그 실패를 방어 장치로 다시 만드는 셈이 된다.
 *
 * <p>따라서 IP 총량 준수는 <b>사용자 경로에서 KOPIS 호출을 제거</b>해 호출자를 동기화 하나로
 * 좁힌 뒤에야 성립한다. 그 전까지 이 제한기는 <b>부분적 방어</b>다.
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
     * 호출 슬롯을 얻을 때까지 <b>현재 스레드를 재운다</b>. 배치 경로 전용이라 블로킹이 타당하다.
     *
     * <p>인터럽트되면 상태를 복구하고 그대로 던진다 — 배치 종료 신호를 삼키면 파드가 제때
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
