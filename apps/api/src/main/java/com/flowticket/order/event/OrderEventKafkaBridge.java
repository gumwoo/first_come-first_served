package com.flowticket.order.event;

import com.flowticket.global.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 도메인 이벤트 → Kafka 발행 브리지(S07 Phase 4).
 * <p><b>AFTER_COMMIT</b>에서만 발행한다: 롤백된 트랜잭션의 유령 이벤트(예: 커밋 안 된 결제)를
 * 브로드캐스트하지 않기 위함. 발행 실패는 삼킨다 — 결제는 이미 커밋됐고 DB가 진실원이며,
 * SSE 알림은 best-effort다(브로커 다운이 결제를 실패로 되돌리면 안 됨). max.block.ms로 블록도 바운드.
 */
@Component
public class OrderEventKafkaBridge {

    private static final Logger log = LoggerFactory.getLogger(OrderEventKafkaBridge.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OrderEventKafkaBridge(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderEvent event) {
        try {
            kafkaTemplate.send(KafkaConfig.ORDER_EVENTS_TOPIC, String.valueOf(event.orderId()), event);
        } catch (Exception e) {
            // 브로커 불가 등 — 알림 유실은 감수(DB가 진실원). 결제 흐름엔 영향 없음.
            log.warn("order-events 발행 실패(무시): type={}, orderId={}, cause={}",
                    event.type(), event.orderId(), e.toString());
        }
    }
}
