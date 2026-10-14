package com.flowticket.admin.dto;

/**
 * 운영 대시보드 지표. kafkaConnected는 실 연결 상태,
 * dlqPending은 DLQ 미처리 적체 수.
 * outboxPending·outboxDead는 아웃박스 미발행·격리 행 수 — Kafka 제거 뒤 kafkaConnected·dlqPending을 대신한다(ADR-028).
 */
public record AdminDashboardResponse(
        long totalEvents,
        long paidOrders,
        long revenue,
        boolean kafkaConnected,
        long dlqPending,
        long outboxPending,
        long outboxDead) {}
