package com.flowticket.order.dto;

import com.flowticket.order.domain.Refund;

/** 환불 응답. refundAmount=환불 예정액(결제액-수수료), orderStatus는 처리 후 주문 상태. */
public record RefundResponse(
        Long refundId,
        String orderStatus,
        int refundAmount,
        int fee) {

    public static RefundResponse of(Refund r, String orderStatus) {
        return new RefundResponse(r.getId(), orderStatus, r.getAmount(), r.getFee());
    }
}
