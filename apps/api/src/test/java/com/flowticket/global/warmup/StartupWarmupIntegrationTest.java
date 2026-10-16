package com.flowticket.global.warmup;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 실제 기동에서 워밍업이 돌아 루프백 조회가 성공하는지(포트·보안 허용·타임아웃 배선) 확인한다.
 * 워밍업은 컨텍스트 기동 중(ApplicationRunner)에 끝나므로 테스트 시점에는 결과가 남아 있다.
 * 속성이 달라 컨텍스트를 따로 띄우므로, 끝나면 닫아 커넥션 풀을 돌려준다(IntegrationTestSupport 주석 — 컨텍스트마다 풀 보유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"warmup.enabled=true", "warmup.duration-ms=500", "warmup.threads=1"})
@DirtiesContext
class StartupWarmupIntegrationTest extends IntegrationTestSupport {

    @Autowired StartupWarmup warmup;
    @Autowired MeterRegistry meterRegistry;
    @Autowired TestRestTemplate rest;

    @Test
    void 기동_중_워밍업이_조회를_반복하고_모두_성공한다() {
        WarmupLoop.Result r = warmup.lastResult();

        assertThat(r).as("워밍업이 돌았어야 한다").isNotNull();
        assertThat(r.requests()).isPositive();
        assertThat(r.failures()).as("공개 조회라 실패가 없어야 한다").isZero();
        // 워밍업 요청은 http.server.requests에서 빠져야 한다(WarmupObservationConfig) — 이 컨텍스트는 이 클래스 전용이라
        // /events 타이머가 있다면 워밍업 요청이 새어 들어온 것이다.
        assertThat(meterRegistry.find("http.server.requests").tag("uri", "/events").timers())
                .as("워밍업 요청이 지표에 섞이면 안 된다").isEmpty();

        // 대조: 헤더 없는 일반 요청은 기록돼야 한다. 그래야 위 단언이 "지표가 아예 안 쌓여서 통과"가 아님을 보장한다.
        rest.getForEntity("/events?page=0&size=20", String.class);
        assertThat(meterRegistry.get("http.server.requests").tag("uri", "/events").timer().count())
                .as("일반 요청은 지표에 남는다").isEqualTo(1);
    }
}
