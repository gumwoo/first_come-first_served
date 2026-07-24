package com.flowticket.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 토픽·에러핸들러 선언(S07 Phase 4). 주문 도메인 이벤트 백본 + DLQ.
 * 팩토리/시리얼라이저는 application.yml + Spring Boot 자동설정에 위임.
 */
@Configuration
public class KafkaConfig {

    /** 주문 도메인 이벤트(order.paid 등). 단일 파티션(단일 노드 데모). */
    public static final String ORDER_EVENTS_TOPIC = "order-events";
    /** 재시도 소진 메시지가 넘어가는 Dead Letter Topic. */
    public static final String ORDER_EVENTS_DLT = ORDER_EVENTS_TOPIC + ".DLT";

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return TopicBuilder.name(ORDER_EVENTS_DLT).partitions(1).replicas(1).build();
    }

    /**
     * 컨슈머 예외 시 짧게 재시도(2회) 후 소진되면 &lt;topic&gt;.DLT로 발행.
     * Spring Boot가 이 CommonErrorHandler 빈을 리스너 컨테이너 팩토리에 자동 연결한다.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(300L, 2L)); // 300ms 간격 2회 재시도
    }
}
