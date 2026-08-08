package com.flowticket.dlq.consumer;

import com.flowticket.dlq.domain.DlqMessage;
import com.flowticket.dlq.repository.DlqMessageRepository;
import com.flowticket.global.config.KafkaConfig;
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

    public DlqConsumer(DlqMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * DLT는 <b>불투명한 바이트</b>로 받는다 — 타입으로 받으면 안 된다(TS-020).
     *
     * <p>DLT에는 두 종류가 들어온다: 리스너에서 실패한 <b>정상 이벤트의 JSON</b>과,
     * 애초에 역직렬화에 실패한 <b>원본 바이트</b>다. 후자를 {@code OrderEvent}로 받으려 하면
     * DLT 소비가 또 실패하는데 <b>DLT에는 다시 보낼 곳이 없어</b> 그 자리에서 무한 재시도가 된다.
     * 즉 독성 메시지가 원본 토픽에서 DLT로 이사할 뿐 아무것도 해결되지 않는다.
     *
     * <p>그래서 해석하지 않고 그대로 기록만 한다. 판단(재시도/폐기)은 사람이 admin API로 한다(ADR-008).
     */
    @KafkaListener(topics = KafkaConfig.ORDER_EVENTS_DLT,
            groupId = "${spring.kafka.consumer.group-id:flowticket}-dlq",
            containerFactory = "dltListenerContainerFactory")
    public void onDeadLetter(
            byte[] body,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) byte[] originalTopic,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] exceptionMessage) {

        String topic = originalTopic != null
                ? new String(originalTopic, StandardCharsets.UTF_8)
                : KafkaConfig.ORDER_EVENTS_TOPIC;
        String error = exceptionMessage != null
                ? new String(exceptionMessage, StandardCharsets.UTF_8)
                : null;
        // 정상 이벤트면 JSON 문자열, 독성 메시지면 원본 그대로. 둘 다 사람이 읽을 수 있게 남긴다.
        String payload = body != null ? new String(body, StandardCharsets.UTF_8) : "";

        repository.save(new DlqMessage(topic, payload, error));
    }
}
