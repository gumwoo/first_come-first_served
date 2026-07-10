package com.flowticket.order.dto;

import jakarta.validation.constraints.NotBlank;

/** 결제 요청. method=card|easy(+provider), idempotencyKey는 클라이언트 생성(더블클릭 멱등). */
public record PaymentRequest(
        @NotBlank String method,
        String provider,
        @NotBlank String idempotencyKey) {}
