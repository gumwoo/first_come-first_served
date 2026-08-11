package com.flowticket.order.domain;

import com.flowticket.seat.domain.SeatGrade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주문 좌석 라인. 등급·가격을 주문 시점 스냅샷으로 보관(ADR-004). */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Column(nullable = false)
    private int price;

    /**
     * 좌석 위치 스냅샷(ADR-004). seats를 조인하지 않는 이유는 grade·price와 같다 —
     * 좌석 배치가 바뀌어도 과거 예매의 표기는 그대로여야 한다.
     * V17 이전 주문은 백필했으나, 백필 실패분을 대비해 nullable로 둔다.
     */
    @Column(name = "seat_row", length = 10)
    private String seatRow;

    @Column(name = "seat_col")
    private Integer seatCol;

    @Builder
    private OrderItem(Long orderId, Long seatId, SeatGrade grade, int price,
                      String seatRow, Integer seatCol) {
        this.orderId = orderId;
        this.seatId = seatId;
        this.grade = grade;
        this.price = price;
        this.seatRow = seatRow;
        this.seatCol = seatCol;
    }
}
