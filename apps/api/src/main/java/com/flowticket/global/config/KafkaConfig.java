package com.flowticket.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 토픽 선언(S07 Phase 4). 주문 도메인 이벤트 백본.
 * 팩토리/시리얼라이저는 application.yml + Spring Boot 자동설정에 위임.
 */
@Configuration
public class KafkaConfig {

    /** 주문 도메인 이벤트(order.paid 등). 단일 파티션(단일 노드 데모). */
    public static final String ORDER_EVENTS_TOPIC = "order-events";

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC).partitions(1).replicas(1).build();
    }
}
