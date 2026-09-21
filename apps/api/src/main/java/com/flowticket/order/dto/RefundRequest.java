package com.flowticket.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 환불 요청. idempotencyKey는 클라이언트 생성(더블클릭 멱등), reason은 취소 사유(선택).
 *
 * 길이는 컬럼 폭(refunds·refund_attempts)과 맞춘다. 경계에서 막지 않으면 초과 값이 그대로
 * 내려가 INSERT에서 터지는데, 그 지점이 PG 취소 뒤라 돈만 나가고 DB는 롤백된다.
 */
public record RefundRequest(
        @Size(max = 100) String reason,
        @NotBlank @Size(max = 80) String idempotencyKey) {}
