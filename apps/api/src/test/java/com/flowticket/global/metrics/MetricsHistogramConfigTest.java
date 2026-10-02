package com.flowticket.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 백분위를 쓰려면 히스토그램 버킷이 필요하다.
 *
 * 이 저장소는 클라이언트 백분위(`percentiles`)를 쓰지 않는다. 파드가 3~9개로 오르내려서 인스턴스별
 * p95를 합칠 방법이 없기 때문이다(application.yml 주석). 그래서 지표를 추가할 때 히스토그램을
 * 켜지 않으면 count·sum·max만 남고, "서비스 전체 p95"는 만들 수 없다.
 *
 * 설정 파일을 눈으로 확인하는 것으로는 부족해서 **실제 application.yml을 태워** 버킷이 나오는지 본다.
 * 컨테이너는 필요 없다. 지표 자동설정만 올린다.
 */
class MetricsHistogramConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class));

    @Test
    void 아웃박스_지표는_히스토그램_버킷을_노출한다() {
        runner.run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            Timer.builder("flowticket.outbox.publish.lag").register(registry).record(Duration.ofSeconds(2));
            Timer.builder("flowticket.outbox.publish.ack").register(registry).record(Duration.ofMillis(120));
            Timer.builder("flowticket.outbox.relay.tick").register(registry).record(Duration.ofSeconds(1));
            Timer.builder("flowticket.queue.admit.tick").register(registry).record(Duration.ofMillis(40));

            String scrape = registry.scrape();

            // 이 셋이 없으면 histogram_quantile을 쓸 수 없다 — ADR-022가 재려는 값이 안 나온다.
            assertThat(scrape).contains("flowticket_outbox_publish_lag_seconds_bucket");
            assertThat(scrape).contains("flowticket_outbox_publish_ack_seconds_bucket");
            assertThat(scrape).contains("flowticket_outbox_relay_tick_seconds_bucket");
            assertThat(scrape).contains("flowticket_queue_admit_tick_seconds_bucket");
        });
    }

    /** 상한을 넘는 값이 +Inf 한 칸에 뭉치면 그 구간의 백분위를 복구할 수 없다. 범위도 함께 고정한다. */
    @Test
    void 발행지연_버킷은_분_단위까지_덮는다() {
        runner.run(context -> {
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            Timer.builder("flowticket.outbox.publish.lag").register(registry).record(Duration.ofMinutes(3));

            String scrape = registry.scrape();

            // 상한 5분: 3분짜리 지연이 +Inf가 아니라 유한 버킷에 들어가야 한다.
            assertThat(scrape)
                    .as("브로커 장애 때의 분 단위 지연이 +Inf로 뭉치면 얼마나 밀렸는지 알 수 없다")
                    .containsPattern("flowticket_outbox_publish_lag_seconds_bucket\\{le=\"(1[0-9]{2}|[2-9][0-9])\\.");
        });
    }
}
