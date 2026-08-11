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

    /**
     * 좌석 라인. seatRow·seatCol은 주문 시점 스냅샷이며 V17 이전 주문은 백필했다.
     * 백필되지 않은 행이 있을 수 있어 null을 허용한다 — 화면은 등급만 표시하도록 대비한다.
     */
    public record OrderItemResponse(Long seatId, String grade, int price,
                                    String seatRow, Integer seatCol) {}
}
