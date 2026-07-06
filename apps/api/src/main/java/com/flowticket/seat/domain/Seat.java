package com.flowticket.seat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 개별 좌석. 재고·상태의 진실원. 선점은 조건부 UPDATE로 원자화(ADR-003). */
@Entity
@Table(name = "seats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Column(length = 20)
    private String zone;

    @Column(name = "seat_row", length = 10)
    private String seatRow;

    @Column(name = "seat_col")
    private Integer seatCol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Seat(Long eventId, SeatGrade grade, String zone, String seatRow, Integer seatCol) {
        this.eventId = eventId;
        this.grade = grade;
        this.zone = zone;
        this.seatRow = seatRow;
        this.seatCol = seatCol;
        this.status = SeatStatus.AVAILABLE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }
}
