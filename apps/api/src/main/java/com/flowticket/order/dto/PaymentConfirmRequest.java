package com.flowticket.order.dto;

import jakarta.validation.constraints.NotBlank;

/** 결제창 인증 확정 요청(BE-5). Toss 결제창이 발급한 paymentKey로 서버 승인. */
public record PaymentConfirmRequest(
        @NotBlank String paymentKey) {}
