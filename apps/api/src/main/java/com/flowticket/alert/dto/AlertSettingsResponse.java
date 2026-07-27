package com.flowticket.alert.dto;

/**
 * 운영 알림 상태(S07). 임계치 + 현재 DLQ 적체 + 초과 여부.
 * breached=true면 대시보드에서 경고를 띄운다.
 */
public record AlertSettingsResponse(
        int dlqPendingThreshold,
        long dlqPending,
        boolean breached) {}
