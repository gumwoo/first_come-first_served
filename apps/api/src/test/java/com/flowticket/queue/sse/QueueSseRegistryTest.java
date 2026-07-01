package com.flowticket.queue.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 레지스트리 스모크: 구독 생성/전송 시 예외 없음, 미등록 토큰 전송은 무시. */
class QueueSseRegistryTest {

    private final QueueSseRegistry registry = new QueueSseRegistry(1800);

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
}
