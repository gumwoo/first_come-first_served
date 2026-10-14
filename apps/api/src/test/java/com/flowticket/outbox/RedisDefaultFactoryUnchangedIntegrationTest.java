package com.flowticket.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.outbox.service.OutboxPublisher;
import com.flowticket.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 발행 전용 연결이 기본 Redis 설정을 건드리지 않는지(kafka-removal-design §2.2·§2.3 "기본 팩토리 불변").
 *
 * 전용 팩토리를 빈으로 올리면 RedisConnectionFactory가 둘이 되어 자동 구성이 물러나거나 타입 주입이 모호해진다.
 * 그러면 대기열 Lua·ShedLock·SSE 구독이 쓰는 기본 연결의 타임아웃이 발행용 값(3초)으로 바뀔 수 있다.
 */
@SpringBootTest
class RedisDefaultFactoryUnchangedIntegrationTest extends IntegrationTestSupport {

    @Autowired ApplicationContext context;
    @Autowired OutboxPublisher publisher;

    @Test
    void Redis_연결_빈은_하나이고_기본_명령_타임아웃은_그대로다() {
        assertThat(context.getBeansOfType(RedisConnectionFactory.class)).hasSize(1);
        assertThat(context.getBeansOfType(StringRedisTemplate.class)).hasSize(1);
        assertThat(publisher).isNotInstanceOf(RedisConnectionFactory.class);

        RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);
        assertThat(factory).isInstanceOf(LettuceConnectionFactory.class);
        // spring.data.redis.timeout 미설정 → Lettuce 기본값(RedisURI.DEFAULT_TIMEOUT 60초). 발행용 3초가 섞이면 안 된다.
        assertThat(((LettuceConnectionFactory) factory).getClientConfiguration().getCommandTimeout())
                .isEqualTo(Duration.ofSeconds(60));
    }
}
