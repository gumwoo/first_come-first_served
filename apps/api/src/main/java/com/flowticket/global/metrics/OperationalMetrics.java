package com.flowticket.global.metrics;

import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사람이 개입해야 하는 적체를 지표로 노출한다.
 *
 * 사용자 요청은 전부 성공하는데 뒤에서만 쌓이는 값들이라 에러율·지연에 나타나지 않는다
 * (TS-032·ADR-008·ADR-016).
 *
 * 관리자 콘솔의 임계치(AlertSettings)와 공유하지 않는다. 콘솔 값은 운영 중 바뀌고
 * Prometheus 규칙은 Git에 고정돼 있어, 묶으면 어느 쪽이 기준인지 모호해진다.
 *
 * 게이지는 스크랩마다(15초) COUNT 질의를 한 번씩 돌린다. 부분 인덱스가 걸린 컬럼이라 비용은 작다.
 */
@Slf4j
@Component
public class OperationalMetrics {

    public OperationalMetrics(MeterRegistry registry,
                              OutboxEventRepository outboxRepository,
                              DlqMessageRepository dlqRepository,
                              Clock clock) {
        // 재시도로 풀리지 않아 격리된 이벤트. 0보다 크면 사람이 판단해야 한다(TS-032).
        register(registry, "flowticket.outbox.events", "DEAD",
                r -> outboxRepository.countByStatus(OutboxStatus.DEAD),
                "재시도로 성공할 수 없어 격리된 아웃박스 행 수");
        // 미발행 적체. 브로커 장애의 직접 신호이고, 복구되면 스스로 줄어든다.
        register(registry, "flowticket.outbox.events", "PENDING",
                r -> outboxRepository.countByStatus(OutboxStatus.PENDING),
                "아직 발행되지 않은 아웃박스 행 수");
        // 가장 오래 기다린 미발행 행의 나이. 개수와 달리 "릴레이가 뒤처지는가"를 보여준다.
        // 100건이 1초 만에 빠지는 것과 3건이 10분째 남아 있는 것은 개수로는 구분되지 않는다.
        Gauge.builder("flowticket.outbox.oldest_pending.age", () -> oldestPendingAgeSeconds(outboxRepository, clock))
                .baseUnit("seconds")
                .description("가장 오래 기다린 미발행 아웃박스 행의 나이(초). 없으면 0")
                .register(registry);
        // 소비 실패로 DLQ에 남은 메시지. 판단은 사람이 한다(ADR-008).
        register(registry, "flowticket.dlq.messages", "PENDING",
                r -> dlqRepository.countByStatus(DlqStatus.PENDING),
                "운영자 판단을 기다리는 DLQ 메시지 수");
    }

    /**
     * 미발행 행이 없으면 0이다. NaN으로 두면 "적체 없음"과 "수집 실패"가 같은 값이 된다.
     * 수집 자체가 실패한 경우에만 NaN을 돌려준다(아래 register와 같은 이유).
     */
    private static double oldestPendingAgeSeconds(OutboxEventRepository repository, Clock clock) {
        try {
            LocalDateTime oldest = repository.findOldestCreatedAt(OutboxStatus.PENDING);
            if (oldest == null) {
                return 0;
            }
            return Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toMillis() / 1000.0);
        } catch (Exception e) {
            log.warn("[metrics] 미발행 최장 대기 수집 실패: {}", e.toString());
            return Double.NaN;
        }
    }

    /**
     * 질의 실패를 삼키고 NaN을 돌려준다. DB가 흔들릴 때 스크랩 엔드포인트 전체가 깨지면
     * CPU·지연·HPA 판단용 지표까지 함께 사라진다. NaN은 값 없음으로 처리돼 알림이 오발화하지 않는다.
     */
    private void register(MeterRegistry registry, String name, String status,
                          ToDoubleFunction<Void> query, String description) {
        Gauge.builder(name, () -> {
                    try {
                        return query.applyAsDouble(null);
                    } catch (Exception e) {
                        log.warn("[metrics] {}{{status={}}} 수집 실패: {}", name, status, e.toString());
                        return Double.NaN;
                    }
                })
                .tag("status", status)
                .description(description)
                .register(registry);
    }
}
