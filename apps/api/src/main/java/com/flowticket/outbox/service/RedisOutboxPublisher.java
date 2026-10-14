package com.flowticket.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.global.sse.SsePubSub;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.order.sse.OrderSseRegistry;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 이벤트를 SSE 팬아웃 채널(sse:order)로 발행한다(ADR-028). 모든 파드의 OrderSseRegistry가 구독해
 * 자기 SSE 연결로 전달한다 — 예전 Kafka 소비자가 하던 마지막 홉과 같은 봉투·채널이다.
 *
 * 전용 연결을 쓰는 이유: 대기 상한을 명령 단위로 걸기 위해서다. 기본 커넥션 팩토리는 spring.data.redis.timeout이
 * 비어 있어 Lettuce 기본값(60초)을 따른다. 한 건이 60초를 물면 릴레이 한 틱이 ShedLock 임차(1분)를 넘는다
 * (하네스 백엔드 규칙 22의 tick-budget + send-timeout ≤ 임차 관계가 실제로는 깨진다). 기본 팩토리의 타임아웃을 바꾸면
 * 대기열 Lua·ShedLock·SSE 구독까지 함께 바뀌므로, 발행만 따로 묶는다.
 *
 * 팩토리를 Spring 빈으로 올리지 않는다. RedisConnectionFactory가 둘이 되면 자동 구성이 물러나거나 타입 주입이
 * 모호해진다(kafka-removal-design §2.2). 연결 설정은 기본 팩토리와 같은 프로퍼티(RedisProperties)에서 읽는다.
 *
 * 연결이 아직 없을 때(기동 직후·재연결)는 명령 타임아웃이 아니라 연결 수립 대기가 먼저다. 그래서 연결 타임아웃도 같은 값으로
 * 묶는다(Lettuce 기본 10초). 연결 수립 뒤 handshake도 같은 값으로 묶이므로, 연결이 없는 상태의 한 건 최악은 이 값의
 * 약 2배다(추론 — 측정하지 않았다). 기본값(3초)이면 틱 예산 45초 + 6초 < 임차 60초.
 *
 * 시간 초과 뒤에도 서버가 명령을 늦게 처리해 실제로는 발행됐을 수 있다. 그 행은 PENDING으로 남아 다음 틱에
 * 한 번 더 나간다 — 중복은 프론트의 재조회 1회로 끝난다(ADR-028 §6).
 */
@Component
public class RedisOutboxPublisher implements OutboxPublisher, DisposableBean {

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /**
     * 테스트는 RedisProperties를 직접 만들어 다른 Redis(지연을 넣을 별도 컨테이너)를 가리키게 한다.
     * spring.data.redis.url·sentinel·cluster 설정은 읽지 않는다(이 프로젝트는 host·port·ssl만 쓴다 — application.yml).
     */
    public RedisOutboxPublisher(RedisProperties properties, ClientResources clientResources, ObjectMapper mapper,
                                @Value("${outbox.send-timeout-ms:3000}") long sendTimeoutMs) {
        Duration timeout = Duration.ofMillis(sendTimeoutMs);
        // ClientResources(이벤트 루프·스레드 풀)는 Boot가 만든 것을 같이 쓴다. 따로 만들면 풀이 하나 더 생기고 Lettuce 지표에서도 빠진다.
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .clientResources(clientResources)
                .commandTimeout(timeout)
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                        .build());
        if (properties.getSsl().isEnabled()) {
            client.useSsl(); // ElastiCache는 전송 중 암호화를 켠다(application.yml REDIS_SSL_ENABLED)
        }
        this.connectionFactory = new LettuceConnectionFactory(serverOf(properties), client.build());
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();
        this.redis = new StringRedisTemplate(connectionFactory);
        this.mapper = mapper;
    }

    @Override
    public Long publish(OrderEvent event) {
        String body;
        try {
            body = mapper.writeValueAsString(new SsePubSub.Envelope(
                    String.valueOf(event.orderId()), event.type(), Map.of("orderId", event.orderId())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("아웃박스 봉투 직렬화 실패 eventId=" + event.eventId(), e);
        }
        // 실패·시간 초과는 그대로 던진다(QueryTimeoutException 등). 릴레이가 받아 PENDING을 유지한다.
        return redis.convertAndSend(OrderSseRegistry.CHANNEL, body);
    }

    @Override
    public void destroy() {
        connectionFactory.destroy();
    }

    private static RedisStandaloneConfiguration serverOf(RedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        server.setDatabase(properties.getDatabase());
        if (properties.getUsername() != null) {
            server.setUsername(properties.getUsername());
        }
        if (properties.getPassword() != null) {
            server.setPassword(RedisPassword.of(properties.getPassword()));
        }
        return server;
    }
}
