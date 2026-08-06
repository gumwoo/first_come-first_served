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
 * Redis 전송 중 암호화(TLS)는 <b>인프라와 앱이 짝을 맞춰야만</b> 동작한다.
 *
 * <p>{@code platform} 스택은 ElastiCache를 {@code transit_encryption_enabled = true}로 만든다.
 * TLS를 켠 캐시에 평문으로 붙으면 연결이 전부 실패하고, readiness에 {@code redis}가 있어
 * Pod가 영원히 Ready가 되지 않는다. 반대로 로컬·CI의 Testcontainers Redis는 평문이라
 * 기본값을 켜 두면 개발 흐름과 테스트가 통째로 깨진다.
 *
 * <p>그래서 지켜야 할 것이 <b>두 방향</b>이고, 아래 두 테스트가 각각을 잡는다.
 * 하나만 두면 나머지 방향의 회귀를 놓친다 — "기본이 꺼져 있다"만 보면 스위치가 고장 나도 통과하고,
 * "스위치가 동작한다"만 보면 기본값이 켜져도 통과한다.
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
        // 러너도 컨텍스트를 만들기는 한다. 차이는 **남느냐**다 — 여기서 @SpringBootTest에
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
