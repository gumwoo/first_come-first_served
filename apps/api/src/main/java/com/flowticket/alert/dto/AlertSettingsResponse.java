package com.flowticket.alert.dto;

/**
 * 운영 알림 상태. 아웃박스 격리(DEAD) 임계치 + 현재 격리 수 + 초과 여부.
 * outboxBreached=true면 대시보드에서 경고를 띄운다. DLQ 필드는 Kafka 제거(ADR-028)와 함께 없앴다.
 */
public record AlertSettingsResponse(
        int outboxDeadThreshold,
        long outboxDead,
        boolean outboxBreached) {}
