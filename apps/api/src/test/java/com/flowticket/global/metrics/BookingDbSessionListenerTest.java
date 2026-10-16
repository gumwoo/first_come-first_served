package com.flowticket.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 세션 이벤트 순서대로 시각을 넣어 네 값(acquire·held·execute·statements)이 맞게 쌓이는지 본다. */
class BookingDbSessionListenerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong();

    @BeforeEach
    void attach() {
        Metrics.globalRegistry.add(registry);
    }

    @AfterEach
    void detach() {
        Metrics.globalRegistry.remove(registry);
        registry.close();
    }

    @Test
    void 획득_점유_실행_SQL수를_세션_끝에_남긴다() {
        BookingDbSessionListener l = new BookingDbSessionListener(now::get, "hold");

        at(0);  l.jdbcConnectionAcquisitionStart();
        at(2);  l.jdbcConnectionAcquisitionEnd();     // 획득 2ms
        at(3);  l.jdbcExecuteStatementStart();
        at(5);  l.jdbcExecuteStatementEnd();          // 실행 2ms
        at(6);  l.jdbcExecuteBatchStart();
        at(9);  l.jdbcExecuteBatchEnd();              // 배치 3ms — 1회로 센다
        at(10); l.jdbcConnectionReleaseStart();       // 점유 2→10 = 8ms
        l.end();

        assertThat(timer(BookingDbSessionListener.ACQUIRE).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2.0);
        assertThat(timer(BookingDbSessionListener.HELD).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(8.0);
        assertThat(timer(BookingDbSessionListener.EXECUTE).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(5.0);
        DistributionSummary statements = registry.get(BookingDbSessionListener.STATEMENTS).tag("op", "hold").summary();
        assertThat(statements.count()).isEqualTo(1);
        assertThat(statements.totalAmount()).isEqualTo(2.0);
    }

    @Test
    void 커넥션을_쥔_채_끝나면_끝난_시각까지를_점유로_본다() {
        BookingDbSessionListener l = new BookingDbSessionListener(now::get, "order");

        at(0); l.jdbcConnectionAcquisitionStart();
        at(1); l.jdbcConnectionAcquisitionEnd();
        at(7); l.end();

        assertThat(registry.get(BookingDbSessionListener.HELD).tag("op", "order").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(6.0);
    }

    @Test
    void 예매_동작이_아니면_아무것도_남기지_않는다() {
        BookingDbSessionListener l = new BookingDbSessionListener(now::get, null);

        l.jdbcConnectionAcquisitionStart();
        l.jdbcConnectionAcquisitionEnd();
        l.jdbcExecuteStatementStart();
        l.jdbcExecuteStatementEnd();
        l.jdbcConnectionReleaseStart();
        l.end();

        assertThat(recorded()).isZero();
    }

    @Test
    void 커넥션을_한_번도_얻지_않은_세션은_남기지_않는다() {
        BookingDbSessionListener l = new BookingDbSessionListener(now::get, "pay");

        l.end();

        assertThat(recorded()).isZero();
    }

    /**
     * 기록 건수 합. 전역 레지스트리는 JVM 하나에서 공유돼 다른 테스트가 만든 미터가 이 레지스트리에도 생긴다(count 0) —
     * "미터가 없다"가 아니라 "기록이 없다"를 본다.
     */
    private long recorded() {
        return registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("flowticket.booking.db."))
                .mapToLong(m -> m instanceof Timer t ? t.count()
                        : m instanceof DistributionSummary s ? s.count() : 0)
                .sum();
    }

    private Timer timer(String name) {
        return registry.get(name).tag("op", "hold").timer();
    }

    private void at(long millis) {
        now.set(TimeUnit.MILLISECONDS.toNanos(millis));
    }
}
