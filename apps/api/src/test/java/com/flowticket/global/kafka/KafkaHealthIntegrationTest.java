package com.flowticket.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.support.KafkaIntegrationTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * S07 Phase 4 (4a): 실 브로커가 있을 때 KafkaHealthService가 연결됨(true)을 보고하는지.
 * 브로커 부재(false) 경로는 AdminAuthIntegrationTest(dead-port)가 이미 커버한다.
 */
@SpringBootTest
class KafkaHealthIntegrationTest extends KafkaIntegrationTestSupport {

    @Autowired KafkaHealthService kafkaHealth;

    @Test
    void 실_브로커가_있으면_연결됨을_보고() {
        assertThat(kafkaHealth.isConnected()).isTrue();
    }
}
