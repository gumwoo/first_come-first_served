package com.flowticket.order.event;

/**
 * 주문 도메인 이벤트(S07 Phase 4). Kafka order-events 토픽 페이로드이자
 * 트랜잭션 커밋 후 발행을 위한 Spring 애플리케이션 이벤트로도 쓰인다.
 * type 예: "order.paid". orderId는 주문 식별자.
 */
public record OrderEvent(String type, Long orderId) {}
