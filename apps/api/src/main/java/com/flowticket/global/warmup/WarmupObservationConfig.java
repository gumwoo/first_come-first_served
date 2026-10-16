package com.flowticket.global.warmup;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * 워밍업 요청(StartupWarmup.HEADER)을 http.server.requests 관측에서 뺀다. 새 파드가 뜰 때마다 수천 건의 조회가
 * 지표에 섞이면 측정 세션의 경로별 p95·처리량이 실제 사용자 요청과 달라진다. 워밍업을 켜지 않으면 해당 헤더가 없어 영향이 없다.
 */
@Configuration
public class WarmupObservationConfig {

    @Bean
    public ObservationPredicate skipWarmupRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier() != null
                && server.getCarrier().getHeader(StartupWarmup.HEADER) != null);
    }
}
