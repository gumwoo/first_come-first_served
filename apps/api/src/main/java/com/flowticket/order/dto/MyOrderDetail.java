package com.flowticket.order.dto;

import com.flowticket.order.dto.OrderResponse.OrderItemResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 마이페이지 예매 상세. 좌석 라인은 주문 시점 스냅샷(등급·가격). */
public record MyOrderDetail(
        Long orderId,
        Long eventId,
        String eventTitle,
        String posterUrl,
        String venue,
        LocalDate eventDate,
        String status,
        int amount,
        LocalDateTime paidAt,
        List<OrderItemResponse> items) {}
