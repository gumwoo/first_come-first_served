package com.flowticket.admin.dto;

/**
 * 운영 대시보드 지표. kafkaConnected는 실 연결 상태,
 * dlqPending은 DLQ 미처리 적체 수.
 */
public record AdminDashboardResponse(
        long totalEvents,
        long paidOrders,
        long revenue,
        boolean kafkaConnected,
        long dlqPending) {}
