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

/** 주문 헤더. 좌석 선점(hold)을 결제 대상으로 승격. 상태전이는 조건부 UPDATE(ADR-006). */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "hold_id", nullable = false)
    private Long holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private int amount;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Order(Long eventId, Long userId, Long holdId, int amount, LocalDateTime expiresAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.holdId = holdId;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.status = OrderStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }
}
