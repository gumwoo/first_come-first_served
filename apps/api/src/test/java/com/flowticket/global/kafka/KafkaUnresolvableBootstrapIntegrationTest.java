package com.flowticket.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * TS-016 재현: 브로커 주소가 DNS로 풀리지 않는 상태에서 API가 기동하는가.
 *
 * 예전에는 리스너 컨테이너(스프링 라이프사이클)가 컨슈머를 만들다 실패해 컨텍스트 refresh가 취소됐다 —
 * 이 테스트는 컨텍스트가 뜨지 않아 실패했을 것이다. IntegrationTestSupport를 상속하지 않는 이유: 그쪽은
 * 해석은 되지만 연결되지 않는 주소(localhost:59092)를 쓰고, 그 경우엔 예전에도 기동했다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "jwt.secret=integration-test-secret-0123456789-0123456789-0123456789",
        "flowticket.scheduling.enabled=false",
        // 테스트 동안 재시도가 돌지 않게(결과는 같다 — 해석되지 않는 주소는 계속 실패한다).
        "kafka.listener.start-retry-interval=PT1H",
})
class KafkaUnresolvableBootstrapIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestContainers.datasource(registry);
        // .invalid는 해석되지 않도록 예약된 TLD다(RFC 2606).
        registry.add("spring.kafka.bootstrap-servers", () -> "flowticket-kafka-bootstrap.does-not-exist.invalid:9092");
    }

    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired HealthEndpoint healthEndpoint;

    @Test
    void 브로커_DNS가_풀리지_않아도_기동하고_readiness는_UP이다() {
        // 여기까지 왔다는 것 자체가 컨텍스트 기동 성공이다.
        assertThat(listenerRegistry.getListenerContainers())
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.isRunning()).isFalse());
        // readiness는 DB·Redis만 본다(Kafka는 일부러 제외) — 브로커가 없어도 트래픽을 받는다.
        assertThat(healthEndpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
    }
}
