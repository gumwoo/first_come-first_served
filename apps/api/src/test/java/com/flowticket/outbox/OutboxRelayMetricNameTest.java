package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.outbox.repository.OutboxEventRepository;
import com.flowticket.outbox.service.OutboxPublisher;
import com.flowticket.outbox.service.OutboxRelay;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * 대시보드(dashboard-flowticket.yaml 아웃박스 행)가 참조하는 릴레이 지표 이름이 Prometheus 스크랩 출력에 그대로 나오는지.
 * Micrometer 이름(점 표기)과 Prometheus 이름(밑줄 + 카운터 _total 접미사)이 달라, 이름이 틀리면 패널이 에러 없이 빈다.
 */
class OutboxRelayMetricNameTest {

    @Test
    void 구독자_0_발행_카운터가_대시보드가_쓰는_이름으로_나온다() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new OutboxRelay(mock(OutboxEventRepository.class), mock(OutboxPublisher.class), new ObjectMapper(),
                100, 45_000L, 7, Clock.systemDefaultZone(), registry);

        assertThat(registry.scrape()).contains("flowticket_outbox_publish_no_receivers_total 0.0");
    }
}
