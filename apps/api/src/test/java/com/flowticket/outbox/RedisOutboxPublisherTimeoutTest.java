package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.order.event.OrderEvent;
import com.flowticket.outbox.service.RedisOutboxPublisher;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 발행기의 대기 상한(kafka-removal-design §2.2 1순위 — Lettuce 명령 타임아웃 = outbox.send-timeout-ms).
 *
 * 지연은 이 테스트만 쓰는 별도 Redis 컨테이너에 CLIENT PAUSE로 넣는다. 공유 컨테이너를 멈추면 다른 테스트와
 * ShedLock이 함께 멈추고, 발행기를 목으로 바꾸면 클라이언트가 시간을 재는지(1순위 설계의 핵심)를 볼 수 없다.
 */
@Testcontainers
class RedisOutboxPublisherTimeoutTest {

    private static final long TIMEOUT_MS = 500;

    @Container
    static GenericContainer<?> slowRedis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    @Test
    void Redis가_응답하지_않으면_명령_타임아웃_안에_예외로_돌아오고_풀리면_다시_발행된다() throws Exception {
        RedisProperties properties = new RedisProperties();
        properties.setHost(slowRedis.getHost());
        properties.setPort(slowRedis.getMappedPort(6379));
        RedisOutboxPublisher publisher = new RedisOutboxPublisher(properties, new ObjectMapper(), TIMEOUT_MS);
        try {
            assertThat(publisher.publish(OrderEvent.of("order.paid", 1L))).isNotNull(); // 연결을 먼저 맺는다

            slowRedis.execInContainer("redis-cli", "CLIENT", "PAUSE", "3000", "ALL");
            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> publisher.publish(OrderEvent.of("order.paid", 2L)))
                    .isInstanceOf(RuntimeException.class);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            // 60초(Lettuce 기본값)가 아니라 설정한 상한 근처에서 돌아온다. 여유는 CI 지연을 감안한 값이다.
            assertThat(elapsedMs).as("발행 대기(ms)").isLessThan(2_000);

            // 일시 정지가 풀리면 같은 발행기로 다시 나간다(연결이 망가지지 않는다).
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(publisher.publish(OrderEvent.of("order.paid", 3L))).isNotNull());
        } finally {
            publisher.destroy();
        }
    }
}
