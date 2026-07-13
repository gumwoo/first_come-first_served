package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.order.dto.PaymentWebhookRequest;
import com.flowticket.order.service.PaymentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG(Toss) 웹훅 수신. 인증(Bearer) 없이 공개 — 대신 서비스에서 secret 대조로 위조를 막는다(ADR-005).
 * 가상계좌 입금(DEPOSIT_CALLBACK) → VBANK_WAITING→PAID 확정(멱등, Toss 최대 7회 재전송 대비).
 */
@RestController
public class PaymentWebhookController {

    private final PaymentService paymentService;

    public PaymentWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/webhooks/payments")
    public ApiResponse<Void> receive(@RequestBody PaymentWebhookRequest req) {
        paymentService.handleVbankDepositWebhook(req.orderId(), req.status(), req.secret());
        return ApiResponse.ok(null);
    }
}
