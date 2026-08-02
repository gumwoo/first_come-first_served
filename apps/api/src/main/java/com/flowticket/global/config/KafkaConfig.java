package com.flowticket.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
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

    /** 주문 도메인 이벤트(order.paid 등). */
    public static final String ORDER_EVENTS_TOPIC = "order-events";
    /** 재시도 소진 메시지가 넘어가는 Dead Letter Topic. */
    public static final String ORDER_EVENTS_DLT = ORDER_EVENTS_TOPIC + ".DLT";

    /**
     * 토픽 파라미터는 환경별로 다르다 — 로컬·CI는 단일 브로커라 1/1이어야 하고(RF가 브로커 수를 넘으면
     * 생성 실패), 운영(Strimzi 3브로커)은 파티션 N·RF 3으로 병렬성과 HA를 얻는다. 그래서 코드에 박지 않고
     * 설정으로 뺀다. 파티션은 나중에 늘릴 수 있지만 <b>RF는 생성 후 이 방식으로 못 바꾼다</b> —
     * 운영에서는 Strimzi {@code KafkaTopic} CR이 권위를 갖고 여기 값과 일치시킨다.
     */
    private final int partitions;
    private final int replicas;

    public KafkaConfig(@Value("${kafka.topic.partitions:1}") int partitions,
                       @Value("${kafka.topic.replicas:1}") int replicas) {
        this.partitions = partitions;
        this.replicas = replicas;
    }

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic orderEventsDltTopic() {
        return TopicBuilder.name(ORDER_EVENTS_DLT).partitions(partitions).replicas(replicas).build();
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
