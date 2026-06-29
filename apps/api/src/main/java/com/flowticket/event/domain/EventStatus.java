package com.flowticket.event.domain;

/** 공연 상태. contracts/enums.yaml EventStatus 와 일치. */
public enum EventStatus {
    DRAFT,
    SCHEDULED,
    ON_SALE,
    PAUSED,
    SOLD_OUT,
    CLOSED
}
