package com.flowticket.queue.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 레지스트리 스모크: 구독 생성/전송 시 예외 없음, 미등록 토큰 전송은 무시.
 *  pubSub=null → 로컬 전달 폴백(팬아웃 없는 유닛 컨텍스트). 팬아웃은 SseFanoutIntegrationTest가 검증. */
class QueueSseRegistryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final QueueSseRegistry registry = new QueueSseRegistry(1800, null, meters);

    @Test
    void 구독은_emitter를_생성한다() {
        SseEmitter emitter = registry.subscribe("tok-1");
        assertThat(emitter).isNotNull();
    }

    @Test
    void 미등록_토큰_전송은_무시된다() {
        assertThatCode(() -> registry.send("none", "queue.admitted", Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void 구독후_전송은_예외없이_버퍼링된다() {
        registry.subscribe("tok-2"); // MVC 초기화 전 send는 SseEmitter가 버퍼링
        assertThatCode(() -> registry.send("tok-2", "queue.admitted", Map.of("redirect", "/x")))
                .doesNotThrowAnyException();
    }

    /** SSE capacity를 재려면 파드가 든 연결 수가 보여야 한다(loadtest-100k-plan §5.1). */
    @Test
    void 연결_수_게이지는_등록된_연결을_센다() {
        registry.subscribe("tok-a");
        registry.subscribe("tok-b");

        assertThat(meters.get("flowticket.queue.sse.connections").gauge().value()).isEqualTo(2.0);
    }

    /**
     * 유지 중 전송 실패는 연결을 격리하고 실패 수를 센다. 실패가 늘면 그만큼 클라이언트가 폴링으로
     * 넘어가 부하 형태가 바뀐다(§2.5) — 그 전환이 보여야 한다.
     */
    @Test
    void 전송_실패는_격리되고_실패_수가_오른다() {
        SseEmitter emitter = registry.subscribe("tok-dead");
        emitter.complete(); // 이미 닫힌 연결 — 이후 send는 IllegalStateException

        registry.deliverLocal("tok-dead", "queue.admitted", Map.of());

        assertThat(meters.get("flowticket.queue.sse.send.failures").tag("phase", "deliver").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("flowticket.queue.sse.connections").gauge().value()).isZero();
    }
}
