package com.flowticket.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowticket.order.repository.OrderRepository;
import com.flowticket.outbox.domain.OutboxStatus;
import com.flowticket.outbox.repository.OutboxEventRepository;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Duration;
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
 *
 * 값은 refresh()가 DB에서 세어 두고 게이지는 그 값만 읽는다. 배경 갱신은 끄고(refreshEnabled=false) 직접 부른다.
 */
class OperationalMetricsTest {

    /** 나이를 재는 지표라 시계를 테스트가 움직인다(ADR-018). */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-21T03:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private PrometheusMeterRegistry registry;
    private OutboxEventRepository outboxRepository;
    private OrderRepository orderRepository;
    private MutableClock clock;
    private OperationalMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        outboxRepository = mock(OutboxEventRepository.class);
        orderRepository = mock(OrderRepository.class);
        clock = new MutableClock();
        when(orderRepository.countOversoldSeats()).thenReturn(0L);
        when(outboxRepository.countByStatus(OutboxStatus.DEAD)).thenReturn(2L);
        when(outboxRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(7L);
        // 가장 오래 기다린 미발행 행: 정확히 90초 전
        when(outboxRepository.findOldestCreatedAt(OutboxStatus.PENDING))
                .thenReturn(LocalDateTime.now(clock).minusSeconds(90));
        metrics = new OperationalMetrics(registry, outboxRepository, orderRepository, clock, false);
    }

    @Test
    @DisplayName("스크랩은 DB를 부르지 않는다. 첫 갱신 전 값은 0이 아니라 값 없음이다")
    void 스크랩은_DB를_부르지_않는다() {
        String scrape = registry.scrape();

        // DB가 느릴 때 지표 응답 전체가 늦어지던 원인(IMP-054 §5.2).
        verifyNoInteractions(outboxRepository, orderRepository);
        assertThat(scrape).contains("flowticket_outbox_events{status=\"DEAD\"} NaN");
        assertThat(registry.get("flowticket.metrics.snapshot.age").gauge().value()).isNaN();
    }

    @Test
    @DisplayName("미발행 최장 대기는 개수와 별개로 노출되고, 읽는 시각 기준으로 늘어난다")
    void 미발행_최장_대기가_노출된다() {
        metrics.refresh();

        // 적체 개수만으로는 "릴레이가 뒤처지는가"를 알 수 없다(ADR-022).
        assertThat(registry.scrape()).contains("flowticket_outbox_oldest_pending_age_seconds");
        assertThat(registry.get("flowticket.outbox.oldest_pending.age").gauge().value()).isEqualTo(90.0);
        // 갱신 사이에도 나이는 계속 는다. 묵은 값을 그대로 내면 갱신 주기만큼 덜 찍힌다.
        clock.advance(Duration.ofSeconds(8));
        assertThat(registry.get("flowticket.outbox.oldest_pending.age").gauge().value()).isEqualTo(98.0);
    }

    @Test
    @DisplayName("알림 규칙이 참조하는 지표 이름·라벨 그대로 노출된다")
    void 지표_이름이_규칙과_일치한다() {
        metrics.refresh();
        String scrape = registry.scrape();

        assertThat(scrape)
                .as("FlowticketOutboxDeadRows가 참조하는 이름")
                .contains("flowticket_outbox_events{status=\"DEAD\"} 2.0");
        assertThat(scrape)
                .as("FlowticketOutboxBacklog")
                .contains("flowticket_outbox_events{status=\"PENDING\"} 7.0");
    }

    @Test
    @DisplayName("값이 얼마나 묵었는지 노출된다")
    void 갱신_뒤_지난_시간이_노출된다() {
        metrics.refresh();
        clock.advance(Duration.ofSeconds(30));

        assertThat(registry.scrape()).contains("flowticket_metrics_snapshot_age_seconds 30.0");
    }

    @Test
    @DisplayName("값이 묵은 시간은 세기 시작한 시각부터 잰다(뒤 질의를 기다린 시간이 앞 값의 나이에 들어간다)")
    void 묵은_시간은_세기_시작한_시각부터다() {
        // 마지막 질의가 커넥션을 12초 기다린 상황
        when(outboxRepository.findOldestCreatedAt(OutboxStatus.PENDING)).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(12));
            return null;
        });

        metrics.refresh();

        assertThat(registry.get("flowticket.metrics.snapshot.age").gauge().value()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("DB 질의가 실패해도 그 값만 값 없음이 되고 나머지는 살아 있다")
    void 질의_실패는_격리된다() {
        when(outboxRepository.countByStatus(any())).thenThrow(new RuntimeException("db down"));

        metrics.refresh();
        String scrape = registry.scrape();

        // 하나가 실패했다고 다른 값까지 지우면 관측을 위해 넣은 것이 관측을 없앤다.
        assertThat(scrape).contains("flowticket_outbox_events{status=\"PENDING\"} NaN");
        assertThat(scrape)
                .as("무관한 지표는 살아 있어야 한다")
                .contains("flowticket_seat_oversold 0.0");
    }

    /** 부하 시험의 초과판매 실시간 중단 조건이 이 이름을 읽는다(loadtest-100k-plan §3.3). */
    @Test
    @DisplayName("초과판매 좌석 수가 노출된다")
    void 초과판매_좌석_수가_노출된다() {
        when(orderRepository.countOversoldSeats()).thenReturn(1L);

        metrics.refresh();

        assertThat(registry.scrape()).contains("flowticket_seat_oversold 1.0");
    }

    /** 질의 실패를 0으로 돌려주면 "초과판매 없음"과 구분되지 않는다. */
    @Test
    @DisplayName("초과판매 질의 실패는 0이 아니라 값 없음이다")
    void 초과판매_질의_실패는_NaN이다() {
        when(orderRepository.countOversoldSeats()).thenThrow(new RuntimeException("db down"));

        metrics.refresh();

        assertThat(registry.get("flowticket.seat.oversold").gauge().value()).isNaN();
    }
}
