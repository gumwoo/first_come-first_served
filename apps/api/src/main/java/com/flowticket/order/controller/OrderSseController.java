package com.flowticket.order.controller;

import com.flowticket.order.sse.OrderSseRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 주문 실시간(주문별). 결제 완료/실패/입금확인 시 order.paid·order.failed·payment.vbank.deposited push. /sse는 permitAll. */
@RestController
public class OrderSseController {

    private final OrderSseRegistry registry;

    public OrderSseController(OrderSseRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/sse/orders/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        return registry.subscribe(id);
    }
}
