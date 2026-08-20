package com.flowticket.order.dto;

/** 주문 SSE 구독 티켓. 만료되면 프론트가 다시 발급받는다. */
public record SseTicketResponse(String ticket) {}
