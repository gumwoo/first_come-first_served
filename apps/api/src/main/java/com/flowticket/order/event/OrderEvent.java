package com.flowticket.order.event;

import java.util.UUID;

/**
 * 주문 도메인 이벤트(S07 Phase 4). Kafka order-events 토픽 페이로드.
 * type 예: "order.paid". orderId는 주문 식별자.
 *
 * <p>eventId는 <b>소비자 멱등 키</b>다(ADR-010). 아웃박스 경로는 outbox_events 행의 PK를 그대로 쓰므로,
 * 릴레이가 재시도로 같은 이벤트를 여러 번 발행해도(at-least-once) 소비자는 한 번만 처리한다.
 */
public record OrderEvent(String type, Long orderId, UUID eventId) {

    /**
     * eventId를 새로 발급한다(직접 발행·테스트용). 아웃박스 경로는 행 id를 명시적으로 넘기므로 쓰지 않는다.
     * 레코드 생성자를 canonical 하나로 유지해 Jackson 역직렬화 모호성을 없앤다.
     */
    public static OrderEvent of(String type, Long orderId) {
        return new OrderEvent(type, orderId, UUID.randomUUID());
    }
}
