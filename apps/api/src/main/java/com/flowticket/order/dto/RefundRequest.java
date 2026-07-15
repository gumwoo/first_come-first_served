package com.flowticket.order.dto;

import jakarta.validation.constraints.NotBlank;

/** 환불 요청. idempotencyKey는 클라이언트 생성(더블클릭 멱등), reason은 취소 사유(선택). */
public record RefundRequest(
        String reason,
        @NotBlank String idempotencyKey) {}
