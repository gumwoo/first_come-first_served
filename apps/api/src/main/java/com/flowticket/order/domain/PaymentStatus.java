package com.flowticket.order.domain;

/** 결제 시도 상태(enums.yaml PaymentStatus 와 일치). */
public enum PaymentStatus {
    READY,
    APPROVED,
    FAILED,
    CANCELLED,
}
