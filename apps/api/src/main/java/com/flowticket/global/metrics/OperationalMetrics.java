package com.flowticket.global.metrics;

import com.flowticket.order.repository.OrderRepository;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사람이 개입해야 하는 적체를 지표로 노출한다.
 *
 * 사용자 요청은 전부 성공하는데 뒤에서만 쌓이는 값들이라 에러율·지연에 나타나지 않는다
 * (TS-032·ADR-016). DLQ 적체 지표는 Kafka 제거(ADR-028)와 함께 없앴다.
 *
 * 관리자 콘솔의 임계치(AlertSettings)와 공유하지 않는다. 콘솔 값은 운영 중 바뀌고
 * Prometheus 규칙은 Git에 고정돼 있어, 묶으면 어느 쪽이 기준인지 모호해진다.
 *
 * 값은 전용 스레드가 REFRESH_INTERVAL마다 DB에서 세어 두고, 게이지는 그 값만 읽는다. 게이지가 스크랩마다
 * DB를 부르면 DB가 느릴 때 지표 응답 전체가 늦어져 CPU·커넥션 같은 무관한 지표까지 함께 끊겼다(IMP-054 §5.2).
 * 갱신은 공용 @Scheduled 스레드(대기열 승격·아웃박스 릴레이와 같은 스레드 하나)에 올리지 않는다. DB가 느릴 때
 * 갱신이 그 스레드를 붙잡으면 승격·릴레이까지 밀린다. 값이 얼마나 묵었는지는 flowticket.metrics.snapshot.age로 본다.
 */
@Slf4j
@Component
public class OperationalMetrics {

    /** 알림은 5분 이상 지속을 보고(prometheusrule-flowticket.yaml), 부하 시험의 초과판매 감시는 이만큼 늦게 알게 된다. */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);

    private final OutboxEventRepository outboxRepository;
    private final OrderRepository orderRepository;
    private final Clock clock;
    private final boolean refreshEnabled;
    private volatile Snapshot snapshot = Snapshot.NONE;
    private ScheduledExecutorService executor;

    /**
     * @param refreshEnabled 통합테스트는 배경 작업을 끈다(SchedulingConfig). 끄면 refresh()를 직접 불러야 값이 생긴다.
     */
    public OperationalMetrics(MeterRegistry registry,
                              OutboxEventRepository outboxRepository,
                              OrderRepository orderRepository,
                              Clock clock,
                              @Value("${flowticket.scheduling.enabled:true}") boolean refreshEnabled) {
        this.outboxRepository = outboxRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
        this.refreshEnabled = refreshEnabled;
        // 재시도로 풀리지 않아 격리된 이벤트. 0보다 크면 사람이 판단해야 한다(TS-032).
        Gauge.builder("flowticket.outbox.events", () -> snapshot.dead())
                .tag("status", "DEAD")
                .description("재시도로 성공할 수 없어 격리된 아웃박스 행 수")
                .register(registry);
        // 미발행 적체. 발행 대상(Redis) 장애의 직접 신호이고, 복구되면 스스로 줄어든다.
        Gauge.builder("flowticket.outbox.events", () -> snapshot.pending())
                .tag("status", "PENDING")
                .description("아직 발행되지 않은 아웃박스 행 수")
                .register(registry);
        // 가장 오래 기다린 미발행 행의 나이. 개수와 달리 "릴레이가 뒤처지는가"를 보여준다.
        // 100건이 1초 만에 빠지는 것과 3건이 10분째 남아 있는 것은 개수로는 구분되지 않는다.
        // 행의 생성 시각을 세어 두고 나이는 읽을 때 계산해, 갱신 주기만큼 나이가 덜 찍히지 않게 한다.
        Gauge.builder("flowticket.outbox.oldest_pending.age", () -> snapshot.oldestPendingAgeSeconds(clock))
                .baseUnit("seconds")
                .description("가장 오래 기다린 미발행 아웃박스 행의 나이(초). 없으면 0")
                .register(registry);
        // 정상이면 항상 0이다. 0보다 크면 성능 문제가 아니라 정합성 결함이다(loadtest-100k-plan §3.3).
        Gauge.builder("flowticket.seat.oversold", () -> snapshot.oversold())
                .description("결제 완료 주문 둘 이상에 걸린 좌석 수(초과판매). 정상이면 0")
                .register(registry);
        Gauge.builder("flowticket.metrics.snapshot.age", () -> snapshot.ageSeconds(clock))
                .baseUnit("seconds")
                .description("위 운영 지표를 마지막으로 센 뒤 지난 시간(초). 갱신 주기보다 크게 벌어지면 갱신이 DB에서 막혀 있다")
                .register(registry);
    }

    @PostConstruct
    void start() {
        if (!refreshEnabled) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ops-metrics-refresh");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::refreshQuietly, 0, REFRESH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * DB에서 값을 다시 센다. 질의마다 실패를 삼키고 그 값만 NaN으로 둔다. NaN은 값 없음으로 처리돼
     * 알림이 오발화하지 않고, 0으로 두면 "적체 없음"과 "수집 실패"가 같은 값이 된다.
     */
    void refresh() {
        double dead = count("flowticket.outbox.events{status=DEAD}", () -> outboxRepository.countByStatus(OutboxStatus.DEAD));
        double pending = count("flowticket.outbox.events{status=PENDING}", () -> outboxRepository.countByStatus(OutboxStatus.PENDING));
        double oversold = count("flowticket.seat.oversold", orderRepository::countOversoldSeats);
        LocalDateTime oldest = null;
        boolean oldestFailed = false;
        try {
            oldest = outboxRepository.findOldestCreatedAt(OutboxStatus.PENDING);
        } catch (Exception e) {
            log.warn("[metrics] 미발행 최장 대기 수집 실패: {}", e.toString());
            oldestFailed = true;
        }
        snapshot = new Snapshot(dead, pending, oldest, oldestFailed, oversold, clock.instant());
    }

    /** 예약 실행은 예외가 한 번 나면 다음 실행이 멈춘다. 갱신이 조용히 끊기지 않게 모두 삼킨다. */
    private void refreshQuietly() {
        try {
            refresh();
        } catch (Throwable t) {
            log.warn("[metrics] 운영 지표 갱신 실패: {}", t.toString());
        }
    }

    private static double count(String name, LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (Exception e) {
            log.warn("[metrics] {} 수집 실패: {}", name, e.toString());
            return Double.NaN;
        }
    }

    /** 한 번 센 값들. 첫 갱신 전에는 모두 값 없음(NaN)이다. */
    private record Snapshot(double dead, double pending, LocalDateTime oldestPending, boolean oldestFailed,
                            double oversold, Instant refreshedAt) {

        static final Snapshot NONE = new Snapshot(Double.NaN, Double.NaN, null, true, Double.NaN, null);

        /** 미발행 행이 없으면 0이다. 수집 자체가 실패한 경우에만 NaN이다. */
        double oldestPendingAgeSeconds(Clock clock) {
            if (oldestFailed) {
                return Double.NaN;
            }
            if (oldestPending == null) {
                return 0;
            }
            return Math.max(0, Duration.between(oldestPending, LocalDateTime.now(clock)).toMillis() / 1000.0);
        }

        double ageSeconds(Clock clock) {
            return refreshedAt == null ? Double.NaN : Duration.between(refreshedAt, clock.instant()).toMillis() / 1000.0;
        }
    }
}
