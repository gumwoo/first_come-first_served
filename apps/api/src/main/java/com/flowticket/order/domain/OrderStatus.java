package com.flowticket.order.domain;

/** 주문 상태(enums.yaml OrderStatus 와 일치). 전이는 ADR-006(조건부 UPDATE). */
public enum OrderStatus {
    PENDING,
    VBANK_WAITING,
    PAID,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED,
}
