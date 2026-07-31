package com.flowticket.outbox.domain;

/**
 * 아웃박스 발행 상태(S08, ADR-010). contracts/enums.yaml OutboxStatus 와 일치.
 * PENDING=적재됨/미발행(릴레이 대상), PUBLISHED=Kafka 발행 성공(7일 보존 후 purge).
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED
}
