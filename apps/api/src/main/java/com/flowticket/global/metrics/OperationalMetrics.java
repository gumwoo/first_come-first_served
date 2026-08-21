package com.flowticket.global.metrics;

import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사람이 개입해야 하는 적체를 지표로 노출한다.
 *
 * <p><b>왜 필요한가</b>: 이 값들은 <b>사용자 요청은 전부 성공하는데 뒤에서만 쌓이는</b> 종류다.
 * 에러율·지연에 나타나지 않으므로, 세어서 내보내지 않으면 <b>알아챌 방법 자체가 없다.</b>
 * 실제로 TS-032·ADR-008·ADR-016이 각각 "발견 경로가 없다"를 한계로 남겼다.
 *
 * <p>관리자 콘솔의 임계치({@code AlertSettings})와는 역할이 다르다 — 그쪽은 <b>볼 때</b>
 * 알려주고, 이쪽은 <b>안 봐도</b> 알려주기 위한 입력이다. 임계치를 공유하지 않는다:
 * 콘솔 값은 운영 중 바꿀 수 있고 Prometheus 규칙은 Git에 고정돼, 하나로 묶으면 어느 쪽이
 * 진실인지 모호해진다.
 *
 * <p>⚠️ 게이지는 <b>스크랩 시점에</b> 평가된다 — 스크랩 주기(15초, servicemonitor-api.yaml)마다
 * COUNT 질의가 한 번씩 돈다.
 * 대상은 부분 인덱스가 걸린 상태 컬럼이라 싸지만, 공짜는 아니다.
 */
@Slf4j
@Component
public class OperationalMetrics {

    public OperationalMetrics(MeterRegistry registry,
                              OutboxEventRepository outboxRepository,
                              DlqMessageRepository dlqRepository) {
        // 재시도로 풀리지 않아 격리된 이벤트. 0보다 크면 사람이 판단해야 한다(TS-032).
        register(registry, "flowticket.outbox.events", "DEAD",
                r -> outboxRepository.countByStatus(OutboxStatus.DEAD),
                "재시도로 성공할 수 없어 격리된 아웃박스 행 수");
        // 미발행 적체. 브로커 장애의 직접 신호이고, 복구되면 스스로 줄어든다.
        register(registry, "flowticket.outbox.events", "PENDING",
                r -> outboxRepository.countByStatus(OutboxStatus.PENDING),
                "아직 발행되지 않은 아웃박스 행 수");
        // 소비 실패로 DLQ에 남은 메시지. 판단은 사람이 한다(ADR-008).
        register(registry, "flowticket.dlq.messages", "PENDING",
                r -> dlqRepository.countByStatus(DlqStatus.PENDING),
                "운영자 판단을 기다리는 DLQ 메시지 수");
    }

    /**
     * 질의 실패를 삼키고 NaN을 돌려준다.
     *
     * <p>DB가 흔들릴 때 <b>지표 수집이 스크랩 엔드포인트 전체를 깨뜨리면 안 된다</b> —
     * 그러면 이 지표뿐 아니라 CPU·지연·HPA 판단용 지표까지 함께 사라진다. 관측을 위해 넣은 것이
     * 관측을 없애는 셈이다. NaN은 Prometheus에서 값 없음으로 처리되어 알림이 오발화하지 않는다.
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
