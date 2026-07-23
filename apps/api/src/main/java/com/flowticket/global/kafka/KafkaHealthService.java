package com.flowticket.global.kafka;

import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

/**
 * Kafka 연결 상태 점검(S07 Phase 4). 대시보드 kafkaConnected 지표에 사용.
 * AdminClient로 클러스터를 짧은 타임아웃 안에 describe해 성공하면 연결됨으로 본다.
 * 브로커 미가용이어도 예외를 삼켜 false만 반환(대시보드가 죽지 않게).
 */
@Service
public class KafkaHealthService {

    private static final int TIMEOUT_MS = 1500;

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthService(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    public boolean isConnected() {
        try (AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            client.describeCluster(new DescribeClusterOptions().timeoutMs(TIMEOUT_MS))
                    .nodes().get(2, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
