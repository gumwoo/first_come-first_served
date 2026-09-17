package com.flowticket.event.kopis;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * KOPIS 호출용 RestClient 두 개. 타임아웃을 반드시 건다.
 *
 * <p>이 이미지의 RestClient는 JDK HttpClient로 떨어져 타임아웃 기본값이 없다. 응답 없는 KOPIS가
 * 톰캣 스레드를 묶어도 readiness는 UP이라 K8s가 빼주지 않는다(TS-028).
 * detail은 사용자가 기다리므로 짧게, sync는 배치라 넉넉히 준다.
 */
@Configuration
public class KopisClientConfig {

    /** 사용자 요청 경로(GET /events/{id})에서 쓴다. 기다리는 것보다 포기하는 편이 낫다. */
    @Bean
    public RestClient kopisDetailClient(RestClient.Builder builder,
                                        @Value("${kopis.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl)
                .requestFactory(factory(Duration.ofSeconds(2), Duration.ofSeconds(3)))
                .build();
    }

    /**
     * 관리자 동기화 잡에서 쓴다. 목록 조회는 rows=100까지 받아 상세보다 오래 걸릴 수 있고,
     * 이 경로로 공연 1,446건을 수집했다 — 여기에 사용자용 타임아웃을 걸면 잘 돌던 시딩이 깨진다.
     */
    @Bean
    public RestClient kopisSyncClient(RestClient.Builder builder,
                                      @Value("${kopis.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl)
                .requestFactory(factory(Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .build();
    }

    private static ClientHttpRequestFactory factory(Duration connect, Duration read) {
        return ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connect)
                .withReadTimeout(read));
    }
}
