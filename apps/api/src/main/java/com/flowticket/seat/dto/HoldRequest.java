package com.flowticket.seat.dto;

import java.util.List;

/** 좌석 선점 요청. queueToken은 S03 대기열 입장 토큰(ADMITTED 검증). */
public record HoldRequest(List<Long> seatIds, String queueToken) {}
