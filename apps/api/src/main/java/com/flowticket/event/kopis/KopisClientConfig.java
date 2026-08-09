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
 * KOPIS 호출용 RestClient 두 개. <b>타임아웃을 반드시 건다.</b>
 *
 * <p>⚠️ 지정하지 않으면 사실상 무한 대기다. RestClient는 request factory를 주지 않으면
 * 클래스패스에서 고르는데, 이 이미지에는 Apache HttpClient5·Jetty·OkHttp가 없어(파드에서 확인)
 * JDK HttpClient로 떨어진다. JDK HttpClient는 connect/request 타임아웃 기본값이 없다.
 *
 * <p>그래서 KOPIS가 응답을 주지 않고 연결만 물고 있으면 톰캣 스레드가 그대로 묶인다. 스레드
 * 풀이 마르면 {@code /events/{id}}뿐 아니라 <b>API 전체가 멎는다.</b> 게다가 readiness는
 * DB·Redis만 보므로 파드는 계속 UP이고 K8s가 빼주지도 않는다 —
 * "에러 없이 조용히 멈추는" TS-020과 같은 실패 형태다.
 *
 * <p><b>두 개로 나눈 이유</b>는 두 호출의 요구가 반대이기 때문이다.
 * <pre>
 *   detail : 사용자가 기다린다        → 짧게 끊는다. 상세는 없어도 응답할 수 있다(폴백 존재)
 *   sync   : 관리자 배치, 아무도 안 기다린다 → 넉넉히 준다. 여기에 3초를 걸면 시딩이 깨진다
 * </pre>
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
