package com.flowticket.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 개별 결제 시도. method/provider는 계약값 문자열(card/easy/vbank, kakaopay…). idempotencyKey UNIQUE. */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(length = 20)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false)
    private int amount;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "pg_tid", length = 100)
    private String pgTid;

    @Column(name = "vbank_account", length = 50)
    private String vbankAccount;

    @Column(name = "vbank_secret", length = 64)
    private String vbankSecret;

    @Column(name = "deposit_deadline")
    private LocalDateTime depositDeadline;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Payment(Long orderId, String method, String provider, int amount, String idempotencyKey) {
        this.orderId = orderId;
        this.method = method;
        this.provider = provider;
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.READY;
        this.createdAt = LocalDateTime.now();
    }

    public void approve(String pgTid) {
        this.status = PaymentStatus.APPROVED;
        this.pgTid = pgTid;
        this.approvedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }

    /** 무통장 — 가상계좌·입금기한·secret 배정(상태는 READY 유지, 입금 웹훅 확인 시 approve). */
    public void assignVbank(String account, LocalDateTime deadline, String secret) {
        this.vbankAccount = account;
        this.depositDeadline = deadline;
        this.vbankSecret = secret;
    }
}
