package com.flowticket.order.event;

import java.util.UUID;

/**
 * 주문 도메인 이벤트. 아웃박스 행의 payload(ADR-010) — 릴레이가 읽어 Redis 채널 sse:order로 내보낸다(ADR-028).
 * type 예: "order.paid". orderId는 주문 식별자.
 *
 * eventId는 outbox_events 행의 PK와 같다(추적용). Kafka 시기에는 소비자 멱등 키였다. 지금은 중복 발행(at-least-once)을
 * 프론트의 재조회 1회가 흡수한다(ADR-028).
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
