package com.flowticket.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 대기열 상태 지표. 부하 시험의 실시간 중단 조건(over-admit, 카운터 어긋남)이 이 값을 읽는다
 * (loadtest-100k-plan §3.3). 이름이 바뀌면 감시가 오류 없이 아무것도 못 보게 되므로 스크랩 출력으로 고정한다.
 */
class QueueMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final QueueMetrics metrics = new QueueMetrics(registry, 100);

    @Test
    void 이벤트별_대기_입장_카운터를_내보낸다() {
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 9_900, 100, 100)), Set.of(7L));

        assertThat(gauge("flowticket.queue.waiting", "7")).isEqualTo(9_900);
        assertThat(gauge("flowticket.queue.admitted", "7")).isEqualTo(100);
        assertThat(gauge("flowticket.queue.admit_count", "7")).isEqualTo(100);
        assertThat(registry.get("flowticket.queue.capacity").gauge().value()).isEqualTo(100);
        assertThat(registry.scrape())
                .contains("flowticket_queue_waiting{event=\"7\"}")
                .contains("flowticket_queue_admitted{event=\"7\"}")
                .contains("flowticket_queue_admit_count{event=\"7\"}");
    }

    /** 같은 게이지가 다음 틱 값을 읽는다 — 틱마다 재등록하지 않아도 값이 바뀌어야 한다. */
    @Test
    void 다음_틱의_값으로_갱신된다() {
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 500, 100, 100)), Set.of(7L));
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 120, 98, 99)), Set.of(7L));

        assertThat(gauge("flowticket.queue.waiting", "7")).isEqualTo(120);
        assertThat(gauge("flowticket.queue.admitted", "7")).isEqualTo(98);
        assertThat(gauge("flowticket.queue.admit_count", "7")).isEqualTo(99);
    }

    /** 대기열이 비어 활성 목록에서 빠진 이벤트는 시계열도 끝난다. 남겨 두면 마지막 값이 영원히 보인다. */
    @Test
    void 비활성_이벤트의_행은_지운다() {
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 1, 1, 1),
                new QueueMetrics.EventSnapshot(8L, 2, 2, 2)), Set.of(7L, 8L));
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(8L, 3, 3, 3)), Set.of(8L));

        assertThat(registry.find("flowticket.queue.waiting").tag("event", "7").gauge()).isNull();
        assertThat(gauge("flowticket.queue.waiting", "8")).isEqualTo(3);
    }

    /** 한 틱의 처리 실패로 시계열이 끊기면 그 구간이 "대기 0"과 구분되지 않는다. 직전 값을 유지한다. */
    @Test
    void 처리에_실패한_활성_이벤트는_직전_값을_유지한다() {
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 40, 100, 100)), Set.of(7L));
        metrics.publish(List.of(), Set.of(7L));

        assertThat(gauge("flowticket.queue.waiting", "7")).isEqualTo(40);
    }

    @Test
    void 승격_틱_소요시간을_기록한다() {
        metrics.tickTimer().record(() -> { });

        assertThat(registry.get("flowticket.queue.admit.tick").timer().count()).isEqualTo(1);
    }

    /**
     * 어긋남은 같은 스냅숏에서 계산한 값이다. 스크랩이 admitted와 admit_count를 따로 읽으면 서로 다른 틱의 값이
     * 섞일 수 있어, 판정은 이 게이지 하나만 본다.
     */
    @Test
    void 어긋남은_같은_스냅숏에서_계산해_내보낸다() {
        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 0, 98, 100)), Set.of(7L));
        assertThat(gauge("flowticket.queue.admit_drift", "7")).isEqualTo(2);

        metrics.publish(List.of(new QueueMetrics.EventSnapshot(7L, 0, 50, 50)), Set.of(7L));
        assertThat(gauge("flowticket.queue.admit_drift", "7")).isZero();
        assertThat(registry.scrape()).contains("flowticket_queue_admit_drift{event=\"7\"}");
    }

    @Test
    void 승격_처리_실패_수를_센다() {
        metrics.tickFailures().increment();

        assertThat(registry.scrape()).contains("flowticket_queue_admit_tick_failures_total 1.0");
    }

    @Test
    void 게이트_폴백_통과_수를_센다() {
        metrics.gateFallback().increment();

        assertThat(registry.scrape()).contains("flowticket_queue_gate_fallback_total 1.0");
    }

    private double gauge(String name, String event) {
        return registry.get(name).tag("event", event).gauge().value();
    }
}
