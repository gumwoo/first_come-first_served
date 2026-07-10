package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.order.dto.PaymentResponse;
import com.flowticket.order.service.PaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발/데모용 무통장 입금 확인 트리거. 실제 운영은 PG 웹훅(BE-5)이 대체.
 * 소유자 본인의 VBANK_WAITING 주문을 PAID로 확정.
 */
@RestController
public class DevPaymentController {

    private final PaymentService paymentService;

    public DevPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/dev/vbank/{id}/deposit")
    public ApiResponse<PaymentResponse> deposit(@PathVariable Long id,
                                                @AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(paymentService.confirmVbankDeposit(userId, id));
    }
}
