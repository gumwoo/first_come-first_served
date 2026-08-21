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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지표 <b>이름</b>을 계약으로 고정한다.
 *
 * <p><b>왜 이름을 테스트하는가</b>: 알림 규칙({@code k8s/monitoring/prometheusrule-flowticket.yaml})이
 * 이 이름을 문자열로 참조한다. 이름이 바뀌면 규칙은 <b>오류 없이 그냥 발화하지 않는다</b> —
 * 실패가 "알림이 안 옴"으로 나타나 알아채기 어렵다.
 *
 * <p>promtool 규칙 테스트로는 이걸 못 잡는다. 규칙과 테스트에 <b>같은 오타</b>를 쓰면 둘 다
 * 통과하기 때문이다. 실제로 렌더링된 스크랩 출력과 대조해야 한다.
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
        new OperationalMetrics(registry, outboxRepository, dlqRepository);
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

        // 이게 없으면 DB가 흔들릴 때 CPU·지연·HPA 판단용 지표까지 함께 사라진다 —
        // 관측을 위해 넣은 것이 관측을 없앤다.
        assertThat(scrape)
                .as("무관한 지표는 살아 있어야 한다")
                .contains("flowticket_dlq_messages{status=\"PENDING\"} 3.0");
    }
}
