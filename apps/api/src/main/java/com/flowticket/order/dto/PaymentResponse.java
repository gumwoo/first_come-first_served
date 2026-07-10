package com.flowticket.order.dto;

/** 결제 응답. paymentStatus(APPROVED/FAILED…) + orderStatus(PAID/PENDING…). */
public record PaymentResponse(Long paymentId, String paymentStatus, String orderStatus) {}
