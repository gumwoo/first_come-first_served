package com.flowticket.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 결제 요청. method=card|easy(+provider), idempotencyKey는 클라이언트 생성(더블클릭 멱등).
 *
 * 길이는 payments 컬럼 폭과 맞춘다(RefundRequest와 같은 이유).
 */
public record PaymentRequest(
        @NotBlank @Size(max = 10) String method,
        @Size(max = 20) String provider,
        @NotBlank @Size(max = 80) String idempotencyKey) {}
