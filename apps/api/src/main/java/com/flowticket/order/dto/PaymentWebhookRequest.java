package com.flowticket.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Toss 가상계좌 입금 웹훅(DEPOSIT_CALLBACK) 본문.
 * orderId는 결제창 규약 "FLOWTICKET-ORDER-{id}", secret은 발급 응답값과 대조해 위조 검증.
 * 알 수 없는 필드(createdAt, transactionKey 등)는 무시한다(스키마 진화 내성).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentWebhookRequest(
        String orderId,
        String status,
        String secret) {}
