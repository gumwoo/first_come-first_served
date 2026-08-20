package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.order.dto.CreateOrderRequest;
import com.flowticket.order.dto.OrderResponse;
import com.flowticket.order.dto.SseTicketResponse;
import com.flowticket.order.service.OrderService;
import com.flowticket.order.service.OrderSseTicketService;
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
    private final OrderSseTicketService sseTicketService;

    public OrderController(OrderService orderService, OrderSseTicketService sseTicketService) {
        this.orderService = orderService;
        this.sseTicketService = sseTicketService;
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

    /**
     * 주문 SSE 구독 티켓 발급(소유자 전용).
     *
     * <p>POST인 이유: 자격증명을 새로 만들어 내보내는 요청이라 캐시·리트라이 대상이 아니다.
     * GET으로 두면 프록시·브라우저 캐시에 티켓이 남을 수 있다.
     */
    @PostMapping("/orders/{id}/sse-ticket")
    public ApiResponse<SseTicketResponse> issueSseTicket(@PathVariable Long id,
                                                         @AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(new SseTicketResponse(sseTicketService.issue(userId, id)));
    }
}
