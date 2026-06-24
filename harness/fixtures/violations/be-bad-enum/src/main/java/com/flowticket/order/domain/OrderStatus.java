package com.flowticket.order.domain;

// VIOLATION: 계약에 없는 상태값 GHOST 추가 → 하네스가 실패해야 함
public enum OrderStatus {
    PENDING,
    VBANK_WAITING,
    PAID,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED,
    GHOST
}
