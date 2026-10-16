package com.flowticket.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 정해진 시간에 멈추고, 실패·예외를 세기만 하며 던지지 않는다(워밍업 실패가 기동을 막으면 안 된다). */
class WarmupLoopTest {

    @Test
    void 시간이_다하면_멈추고_요청_수를_센다() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        // 호출마다 시계를 1ms씩 흘린다 — 10ms면 10회 안팎에서 멈춰야 한다.
        WarmupLoop loop = new WarmupLoop(() -> {
            calls.incrementAndGet();
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            return true;
        }, 10, 1, now::get);

        WarmupLoop.Result r = loop.run();

        assertThat(r.requests()).isEqualTo(calls.get()).isEqualTo(10);
        assertThat(r.failures()).isZero();
        assertThat(r.elapsedMs()).isEqualTo(10);
    }

    @Test
    void 실패와_예외는_세고_던지지_않는다() {
        AtomicLong now = new AtomicLong();
        AtomicInteger n = new AtomicInteger();
        WarmupLoop loop = new WarmupLoop(() -> {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            int i = n.incrementAndGet();
            if (i % 3 == 0) {
                throw new IllegalStateException("연결 거부");
            }
            return i % 3 == 1; // 1·4·7·10 성공, 2·5·8 실패 응답, 3·6·9 예외
        }, 10, 1, now::get);

        WarmupLoop.Result r = loop.run();

        assertThat(r.requests()).isEqualTo(10);
        assertThat(r.failures()).isEqualTo(6);
    }

    @Test
    void 시간이_0이면_호출하지_않는다() {
        AtomicInteger calls = new AtomicInteger();
        WarmupLoop.Result r = new WarmupLoop(() -> calls.incrementAndGet() > 0, 0, 2, System::nanoTime).run();

        assertThat(calls.get()).isZero();
        assertThat(r.requests()).isZero();
    }
}
