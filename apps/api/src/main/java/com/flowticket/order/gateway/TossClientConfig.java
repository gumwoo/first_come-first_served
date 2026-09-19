package com.flowticket.order.gateway;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Toss 호출용 RestClient. 타임아웃을 반드시 건다.
 *
 * 이 호출은 DB 트랜잭션 안이라 응답 없는 PG가 Hikari 커넥션까지 묶는다(TS-028).
 * read 타임아웃은 "모름"이라 미아 승인이 생길 수 있지만 ADR-011 정산이 회수하므로 넉넉히 준다(기본 10초).
 *
 * 어댑터가 직접 빌드하지 않고 여기서 만드는 것은 KopisClientConfig와 같은 이유다.
 * 요청 팩토리를 어댑터 생성자에서 덮어쓰면 MockRestServiceServer가 요청을 가로채지 못해
 * 어댑터가 실제로 무엇을 보내는지 테스트할 수 없다.
 */
@Configuration
@ConditionalOnProperty(name = "payment.gateway", havingValue = "toss")
public class TossClientConfig {

    private static final String BASE_URL = "https://api.tosspayments.com";

    @Bean
    public RestClient tossClient(RestClient.Builder builder,
                                 @Value("${toss.connect-timeout-ms:2000}") long connectTimeoutMs,
                                 @Value("${toss.read-timeout-ms:10000}") long readTimeoutMs) {
        return builder.clone().baseUrl(BASE_URL)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
                .build();
    }
}
