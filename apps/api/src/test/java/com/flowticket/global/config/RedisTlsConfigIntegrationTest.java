package com.flowticket.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Redis TLS는 인프라와 앱이 짝을 맞춰야 한다(ADR-013) — 운영 ElastiCache는 TLS, 로컬·CI는 평문.
 * 두 방향을 각각 잡는다: 기본값은 꺼져 있는가, 스위치를 켜면 실제로 SSL이 켜지는가.
 */
@SpringBootTest
class RedisTlsConfigIntegrationTest extends IntegrationTestSupport {

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @Test
    void 기본값은_평문이다_로컬과_CI가_깨지지_않도록() {
        // application.yml의 ${REDIS_SSL_ENABLED:false}가 실제로 평문으로 바인딩되는지.
        // 이 컨텍스트가 지금 Testcontainers Redis(평문)에 붙어 살아 있다는 것 자체가 증거다.
        assertThat(connectionFactory.isUseSsl())
                .as("기본값이 true가 되면 로컬 개발과 CI의 Redis 연결이 전부 실패한다")
                .isFalse();
    }

    @Test
    void 환경변수로_TLS를_켤_수_있다() {
        // 운영(k8s)에서 REDIS_SSL_ENABLED=true로 주입하는 경로.
        //
        // 러너도 컨텍스트를 만들기는 한다. 차이는 남느냐다 — 여기서 @SpringBootTest에
        // 프로퍼티를 덧붙이면 캐시 키가 갈려 무거운 전체 컨텍스트가 캐시에 하나 더 쌓인 채
        // 살아남는다(IMP-013 §7-2의 자원 압박). 러너는 Redis 자동 구성만 담은 작은 컨텍스트를
        // 잠깐 띄웠다 닫으므로 캐시에 아무것도 남기지 않는다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.ssl.enabled=true")
                .run(context -> assertThat(context.getBean(LettuceConnectionFactory.class).isUseSsl())
                        .as("이 스위치가 죽으면 운영에서 Pod가 Ready가 되지 않는다")
                        .isTrue());
    }
}
