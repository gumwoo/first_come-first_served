package com.flowticket.order.dto;

import jakarta.validation.constraints.NotNull;

/** 주문 생성 요청 — 좌석 선점(hold) 기반. */
public record CreateOrderRequest(@NotNull Long holdId) {}
