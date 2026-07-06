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

/** 이벤트×등급 절대 가격. 결제 가격 단일 진실원(ADR-004). */
@Entity
@Table(name = "event_seat_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSeatPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Column(nullable = false)
    private Integer price;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private EventSeatPrice(Long eventId, SeatGrade grade, Integer price) {
        this.eventId = eventId;
        this.grade = grade;
        this.price = price;
        this.createdAt = LocalDateTime.now();
    }
}
