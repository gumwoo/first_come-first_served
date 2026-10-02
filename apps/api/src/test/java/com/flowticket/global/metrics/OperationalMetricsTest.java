package com.flowticket.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowticket.dlq.domain.DlqStatus;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지표 이름을 계약으로 고정한다.
 *
 * 알림 규칙(k8s/monitoring/prometheusrule-flowticket.yaml)이 이 이름을 문자열로 참조해, 이름이 바뀌면
 * 규칙이 오류 없이 발화하지 않는다. promtool 규칙 테스트는 규칙과 테스트에 같은 오타가 있으면
 * 통과하므로, 렌더링된 스크랩 출력과 대조한다.
 */
class OperationalMetricsTest {

    private PrometheusMeterRegistry registry;
    private OutboxEventRepository outboxRepository;
    private DlqMessageRepository dlqRepository;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        outboxRepository = mock(OutboxEventRepository.class);
        dlqRepository = mock(DlqMessageRepository.class);
        when(outboxRepository.countByStatus(OutboxStatus.DEAD)).thenReturn(2L);
        when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(7L);
        when(dlqRepository.countByStatus(DlqStatus.PENDING)).thenReturn(3L);
        // 가장 오래 기다린 미발행 행: 고정 시계에서 정확히 90초 전
        when(outboxRepository.findOldestCreatedAt(OutboxStatus.PENDING))
                .thenReturn(LocalDateTime.now(CLOCK).minusSeconds(90));
        new OperationalMetrics(registry, outboxRepository, dlqRepository, CLOCK);
    }

    /** 나이를 재는 지표라 시계를 고정한다(ADR-018). */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-21T03:00:00Z"), ZoneId.systemDefault());

    @Test
    @DisplayName("미발행 최장 대기는 개수와 별개로 노출된다")
    void 미발행_최장_대기가_노출된다() {
        String scrape = registry.scrape();

        // 적체 개수만으로는 "릴레이가 뒤처지는가"를 알 수 없다(ADR-022).
        assertThat(scrape).contains("flowticket_outbox_oldest_pending_age_seconds");
        assertThat(registry.get("flowticket.outbox.oldest_pending.age").gauge().value())
                .isEqualTo(90.0);
    }

    @Test
    @DisplayName("알림 규칙이 참조하는 지표 이름·라벨 그대로 노출된다")
    void 지표_이름이_규칙과_일치한다() {
        String scrape = registry.scrape();

        assertThat(scrape)
                .as("FlowticketOutboxDeadRows가 참조하는 이름")
                .contains("flowticket_outbox_events{status=\"DEAD\"} 2.0");
        assertThat(scrape)
                .as("FlowticketOutboxBacklog")
                .contains("flowticket_outbox_events{status=\"PENDING\"} 7.0");
        assertThat(scrape)
                .as("FlowticketDlqBacklog")
                .contains("flowticket_dlq_messages{status=\"PENDING\"} 3.0");
    }

    @Test
    @DisplayName("DB 질의가 실패해도 스크랩 전체를 깨뜨리지 않는다")
    void 질의_실패는_격리된다() {
        when(outboxRepository.countByStatus(any())).thenThrow(new RuntimeException("db down"));

        String scrape = registry.scrape();

        // 이게 없으면 DB가 흔들릴 때 CPU·지연·HPA 판단용 지표까지 함께 사라진다.
        // 관측을 위해 넣은 것이 관측을 없앤다.
        assertThat(scrape)
                .as("무관한 지표는 살아 있어야 한다")
                .contains("flowticket_dlq_messages{status=\"PENDING\"} 3.0");
    }
}
