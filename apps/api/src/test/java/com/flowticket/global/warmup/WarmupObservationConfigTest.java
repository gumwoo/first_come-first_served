package com.flowticket.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 워밍업 헤더가 붙은 서버 요청만 관측에서 빠지고, 나머지는 그대로 남는다. */
class WarmupObservationConfigTest {

    private final ObservationPredicate predicate = new WarmupObservationConfig().skipWarmupRequests();

    @Test
    void 워밍업_요청은_관측에서_뺀다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/events");
        request.addHeader(StartupWarmup.HEADER, "1");

        assertThat(predicate.test("http.server.requests", server(request))).isFalse();
    }

    @Test
    void 일반_요청은_그대로_관측한다() {
        assertThat(predicate.test("http.server.requests", server(new MockHttpServletRequest("GET", "/events"))))
                .isTrue();
    }

    @Test
    void 서버_요청이_아닌_관측은_건드리지_않는다() {
        assertThat(predicate.test("flowticket.outbox.relay.tick", new Observation.Context())).isTrue();
    }

    private static ServerRequestObservationContext server(MockHttpServletRequest request) {
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
