package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.order.dto.PaymentRequest;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 결제 시도(회원, 소유자). 승인/거절 결과를 반환(거절도 200 — 재시도 가능). */
@RestController
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/orders/{id}/payments")
    public ApiResponse<PaymentResponse> pay(@PathVariable Long id,
                                            @AuthenticationPrincipal Long userId,
                                            @Valid @RequestBody PaymentRequest request) {
        return ApiResponse.ok(paymentService.pay(
                userId, id, request.method(), request.provider(), request.idempotencyKey()));
    }
}
