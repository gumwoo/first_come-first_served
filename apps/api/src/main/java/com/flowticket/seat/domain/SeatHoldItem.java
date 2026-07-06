package com.flowticket.seat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 홀드↔좌석 연결(한 홀드가 여러 좌석). */
@Entity
@Table(name = "seat_hold_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatHoldItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_id", nullable = false)
    private Long holdId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Builder
    private SeatHoldItem(Long holdId, Long seatId) {
        this.holdId = holdId;
        this.seatId = seatId;
    }
}
