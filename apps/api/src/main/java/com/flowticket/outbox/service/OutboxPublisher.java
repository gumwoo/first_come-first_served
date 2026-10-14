package com.flowticket.outbox.service;

import com.flowticket.order.event.OrderEvent;

/**
 * 아웃박스 이벤트를 내보내는 곳(ADR-028). 릴레이는 이것이 정상 반환한 뒤에만 PUBLISHED로 마킹한다.
 *
 * 실패는 반드시 예외로 알린다. 삼키면 릴레이는 성공으로 알고 마킹해, 그 이벤트는 다시 나가지 않는다
 * (SsePubSub.publish가 그런 메서드라 여기 쓰지 않는다 — kafka-removal-design §2.1).
 */
@FunctionalInterface
public interface OutboxPublisher {

    /**
     * 발행한다. 호출이 성공하면 받은 구독자 수를 돌려준다(0일 수 있다 — 구독 연결이 없으면 아무도 못 받는다).
     * PUBLISHED의 뜻은 "발행 호출이 성공했다"이지 구독자·사용자가 받았다는 뜻이 아니다(ADR-028 §6).
     */
    Long publish(OrderEvent event);
}
