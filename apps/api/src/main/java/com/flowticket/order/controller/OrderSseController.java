package com.flowticket.order.controller;

import com.flowticket.order.service.OrderSseTicketService;
import com.flowticket.order.sse.OrderSseRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 주문 상태 푸시(SSE).
 *
 * <p>Bearer가 아니라 <b>구독 티켓</b>으로 인가한다 — {@code EventSource}가 헤더를 붙이지 못하기
 * 때문이다. 발급은 {@code POST /orders/{id}/sse-ticket}(Bearer 필요)이 한다.
 */
@RestController
public class OrderSseController {

    private final OrderSseRegistry registry;
    private final OrderSseTicketService ticketService;

    public OrderSseController(OrderSseRegistry registry, OrderSseTicketService ticketService) {
        this.registry = registry;
        this.ticketService = ticketService;
    }

    @GetMapping(value = "/sse/orders/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, @RequestParam(required = false) String ticket) {
        ticketService.verify(ticket, id);
        return registry.subscribe(id);
    }
}
