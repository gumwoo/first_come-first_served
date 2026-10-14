package com.flowticket.admin.dto;

/**
 * 운영 대시보드 지표. outboxPending·outboxDead는 아웃박스 미발행·격리 행 수다.
 * Kafka 연결 상태·DLQ 적체 필드는 Kafka 제거(ADR-028)와 함께 없앴다 — 실패는 아웃박스 행에 남는다.
 */
public record AdminDashboardResponse(
        long totalEvents,
        long paidOrders,
        long revenue,
        long outboxPending,
        long outboxDead) {}
