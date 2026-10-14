package com.flowticket.alert.dto;

/**
 * 운영 알림 상태. 임계치 + 현재 값 + 초과 여부.
 * breached는 DLQ 적체 기준(Kafka 제거 릴리스에서 제거 예정), outboxBreached는 아웃박스 격리(DEAD) 기준이다(ADR-028).
 */
public record AlertSettingsResponse(
        int dlqPendingThreshold,
        long dlqPending,
        boolean breached,
        int outboxDeadThreshold,
        long outboxDead,
        boolean outboxBreached) {}
