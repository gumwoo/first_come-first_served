package com.flowticket.seat.dto;

import java.util.List;

/** 좌석맵 응답: 등급 요약(가격·잔여) + 개별 좌석. */
public record SeatMapResponse(Long eventId, List<GradeInfo> grades, List<SeatInfo> seats) {

    public record GradeInfo(String grade, int price, long total, long available) {}

    public record SeatInfo(Long id, String grade, String zone, String seatRow, Integer seatCol, String status) {}
}
