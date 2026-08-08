package com.flowticket.global.config;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
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

    /**
     * DLT로 나가는 값은 <b>두 종류</b>다 — 이걸 한 직렬화기로 처리할 수 없다(TS-020).
     *
     * <pre>
     *   리스너에서 실패    → 역직렬화는 성공했으므로 값이 OrderEvent → JsonSerializer
     *   역직렬화에서 실패  → 값이 없다. Recoverer가 <b>원본 byte[]</b>를 그대로 싣는다 → ByteArraySerializer
     * </pre>
     *
     * <p>JsonSerializer 하나로 두면 {@code byte[]}가 base64 JSON 문자열로 직렬화돼
     * DLT 소비 쪽에서 다시 역직렬화에 실패한다. <b>독성 메시지가 DLT로 이사할 뿐</b>이고,
     * DLT에는 다시 보낼 곳이 없어 거기서 무한 재시도가 된다.
     *
     * <p>{@code assignable=true}라 {@code OrderEvent}가 {@code Object.class} 매핑에 걸린다.
     */
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public DefaultKafkaProducerFactoryCustomizer dltCapableValueSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());
        DelegatingByTypeSerializer serializer = new DelegatingByTypeSerializer(delegates, true);
        // 커스터마이저가 넘겨주는 팩토리는 DefaultKafkaProducerFactory<?, ?>라 와일드카드 캡처
        // 때문에 그대로는 setValueSerializer를 부를 수 없다. raw 타입으로 좁혀 호출한다.
        return factory -> ((DefaultKafkaProducerFactory) factory).setValueSerializer(serializer);
    }

    /**
     * DLT 전용 리스너 컨테이너 — 값을 <b>해석하지 않고 바이트로</b> 받는다.
     *
     * <p>DLT에는 정상 이벤트의 JSON도, 역직렬화에 실패한 원본 바이트도 들어온다. 후자를 타입으로
     * 받으려 하면 DLT 소비가 또 실패하고, 그 실패는 갈 곳이 없다. 그래서 DLT는 <b>불투명한
     * 바이트</b>로 취급하고 기록만 한다 — 판단은 사람이 admin API로 한다(ADR-008).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> dltListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        // 자동 구성된 컨슈머 설정(부트스트랩·auto-offset-reset 등)은 그대로 물려받고,
        // 역직렬화기만 바꾼다. ErrorHandlingDeserializer의 delegate 설정은 여기선 의미가 없어 뺀다.
        Map<String, Object> props = new HashMap<>(consumerFactory.getConfigurationProperties());
        props.remove("spring.deserializer.value.delegate.class");
        ConsumerFactory<String, byte[]> byteFactory = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new ByteArrayDeserializer());

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(byteFactory);
        // DLT 소비 실패를 다시 DLT로 보내지 않는다(무한 순환). 기본 로깅 핸들러에 맡긴다.
        return factory;
    }
}
