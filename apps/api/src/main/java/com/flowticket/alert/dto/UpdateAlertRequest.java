package com.flowticket.alert.dto;

import jakarta.validation.constraints.Min;

/** 알림 임계치 수정(S07). DLQ 적체 임계치는 0 이상. */
public record UpdateAlertRequest(
        @Min(0) int dlqPendingThreshold) {}
