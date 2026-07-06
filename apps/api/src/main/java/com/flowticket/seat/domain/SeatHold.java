package com.flowticket.seat.domain;

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

/** 좌석 선점. 만료/해제/결제확정으로 상태 전이. */
@Entity
@Table(name = "seat_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatHoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private SeatHold(Long eventId, Long userId, LocalDateTime expiresAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.status = SeatHoldStatus.HELD;
        this.createdAt = LocalDateTime.now();
    }

    public void release() {
        this.status = SeatHoldStatus.RELEASED;
    }

    public void expire() {
        this.status = SeatHoldStatus.EXPIRED;
    }
}
