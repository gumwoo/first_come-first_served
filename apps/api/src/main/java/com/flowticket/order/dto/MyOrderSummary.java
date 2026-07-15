package com.flowticket.order.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 마이페이지 예매 목록 항목. 공연 정보(제목/포스터/일시)는 events 조인 스냅샷. */
public record MyOrderSummary(
        Long orderId,
        Long eventId,
        String eventTitle,
        String posterUrl,
        LocalDate eventDate,
        String status,
        int amount,
        int seatCount,
        LocalDateTime createdAt) {}
