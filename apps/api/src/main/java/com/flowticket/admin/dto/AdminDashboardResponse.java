package com.flowticket.admin.dto;

/**
 * 운영 대시보드 지표(S07 Phase 1 골격). 실제 집계 값.
 * Kafka Consumer Lag·시스템 상태는 Kafka 실구현(Phase 4) 후 채운다 → 현재 kafkaConnected=false.
 */
public record AdminDashboardResponse(
        long totalEvents,
        long paidOrders,
        long revenue,
        boolean kafkaConnected) {}
