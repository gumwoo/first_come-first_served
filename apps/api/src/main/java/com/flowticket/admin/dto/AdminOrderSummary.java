package com.flowticket.admin.dto;

import java.time.LocalDateTime;

/**
 * 운영 주문 목록 항목(S07). 마이페이지와 달리 <b>전 사용자</b> 주문을 노출하므로
 * 주문자 식별(userId/email)을 포함한다. 공연 제목은 events 조인 스냅샷.
 */
public record AdminOrderSummary(
        Long orderId,
        Long eventId,
        String eventTitle,
        Long userId,
        String userEmail,
        String status,
        int amount,
        LocalDateTime createdAt,
        LocalDateTime paidAt) {}
