package com.flowticket.order.dto;

import java.time.LocalDateTime;

/** 결제 응답. vbank면 가상계좌/입금기한 포함(그 외 null). */
public record PaymentResponse(
        Long paymentId,
        String paymentStatus,
        String orderStatus,
        String vbankAccount,
        LocalDateTime depositDeadline) {

    public static PaymentResponse of(Long paymentId, String paymentStatus, String orderStatus) {
        return new PaymentResponse(paymentId, paymentStatus, orderStatus, null, null);
    }

    public static PaymentResponse vbank(Long paymentId, String orderStatus,
                                        String account, LocalDateTime deadline) {
        return new PaymentResponse(paymentId, "READY", orderStatus, account, deadline);
    }
}
