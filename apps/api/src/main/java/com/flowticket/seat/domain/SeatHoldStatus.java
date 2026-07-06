package com.flowticket.seat.domain;

/** 선점 상태. contracts/enums.yaml SeatHoldStatus와 일치(하네스 검사). */
public enum SeatHoldStatus {
    HELD,
    RELEASED,
    EXPIRED,
    CONVERTED,
}
