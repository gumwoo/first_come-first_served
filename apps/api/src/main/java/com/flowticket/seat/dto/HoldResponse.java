package com.flowticket.seat.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 선점 성공 응답. */
public record HoldResponse(Long holdId, List<Long> seatIds, int total, LocalDateTime expiresAt) {}
