package com.flowticket.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 환불 기록(S06). 취소 1건당 1행. idempotency_key UNIQUE로 이중 환불 차단. */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(nullable = false)
    private int amount;

    @Column(nullable = false)
    private int fee;

    @Column(length = 100)
    private String reason;

    @Column(name = "pg_refund_tid", length = 100)
    private String pgRefundTid;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Refund(Long orderId, Long paymentId, int amount, int fee, String reason,
                   String pgRefundTid, String idempotencyKey) {
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.fee = fee;
        this.reason = reason;
        this.pgRefundTid = pgRefundTid;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = LocalDateTime.now();
    }
}
