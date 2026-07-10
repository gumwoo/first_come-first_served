package com.flowticket.order.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 주문 응답. status는 OrderStatus 이름. */
public record OrderResponse(
        Long orderId,
        Long eventId,
        String status,
        int amount,
        LocalDateTime expiresAt,
        List<OrderItemResponse> items) {

    public record OrderItemResponse(Long seatId, String grade, int price) {}
}
