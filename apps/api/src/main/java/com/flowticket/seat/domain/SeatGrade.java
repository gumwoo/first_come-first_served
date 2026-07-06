package com.flowticket.seat.domain;

/** 좌석 등급. contracts/enums.yaml SeatGrade와 일치(하네스 검사). */
public enum SeatGrade {
    // 가격 배수: A=1.0 기준
    VIP,
    R,
    S,
    A,
}
