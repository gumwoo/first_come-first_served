package com.flowticket.admin.dto;

/**
 * 운영 대시보드 지표(S07). kafkaConnected는 실 연결 상태(Phase 4a),
 * dlqPending은 DLQ 미처리 적체 수(Phase 4c).
 */
public record AdminDashboardResponse(
        long totalEvents,
        long paidOrders,
        long revenue,
        boolean kafkaConnected,
        long dlqPending) {}
