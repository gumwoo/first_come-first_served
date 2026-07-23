package com.flowticket.dlq.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowticket.dlq.domain.DlqMessage;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.global.config.KafkaConfig;
import com.flowticket.order.event.OrderEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * DLT 소비 → dlq_messages 적재(S07 Phase 4c).
 * 재시도 소진으로 order-events.DLT에 넘어온 메시지를 운영 조회/재처리용으로 DB에 보존한다.
 * DeadLetterPublishingRecoverer가 실어준 원본 토픽·예외 메시지 헤더를 함께 기록.
 */
@Component
public class DlqConsumer {

    private final DlqMessageRepository repository;
    private final ObjectMapper objectMapper;

    public DlqConsumer(DlqMessageRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaConfig.ORDER_EVENTS_DLT, groupId = "flowticket-dlq")
    public void onDeadLetter(
            OrderEvent event,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) byte[] originalTopic,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] exceptionMessage)
            throws JsonProcessingException {

        String topic = originalTopic != null
                ? new String(originalTopic, StandardCharsets.UTF_8)
                : KafkaConfig.ORDER_EVENTS_TOPIC;
        String error = exceptionMessage != null
                ? new String(exceptionMessage, StandardCharsets.UTF_8)
                : null;
        String payload = objectMapper.writeValueAsString(event);

        repository.save(new DlqMessage(topic, payload, error));
    }
}
