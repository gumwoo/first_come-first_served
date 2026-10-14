package com.flowticket.alert.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 알림 임계치 수정. 아웃박스 격리(DEAD) 임계치는 필수, 0 이상.
 * 이전 화면(P2a)이 함께 보내던 dlqPendingThreshold는 알 수 없는 필드라 무시된다(롤링 배포 중 공존 가능).
 */
public record UpdateAlertRequest(
        @NotNull @Min(0) Integer outboxDeadThreshold) {}
