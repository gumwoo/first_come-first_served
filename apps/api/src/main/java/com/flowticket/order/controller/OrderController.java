package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.order.dto.CreateOrderRequest;
import com.flowticket.order.dto.OrderResponse;
import com.flowticket.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 주문 생성(회원)/조회(소유자). 입출력·매핑만. */
@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public ApiResponse<OrderResponse> create(@AuthenticationPrincipal Long userId,
                                             @Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.create(userId, request.holdId()));
    }

    @GetMapping("/orders/{id}")
    public ApiResponse<OrderResponse> get(@PathVariable Long id,
                                          @AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(orderService.get(id, userId));
    }
}
