package com.flowticket.seat.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.seat.dto.HoldRequest;
import com.flowticket.seat.dto.HoldResponse;
import com.flowticket.seat.dto.SeatMapResponse;
import com.flowticket.seat.service.SeatService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 좌석 조회(공개)/선점(회원+입장)/해제(소유자). 입출력·매핑만. */
@RestController
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @GetMapping("/events/{id}/seats")
    public ApiResponse<SeatMapResponse> seats(@PathVariable Long id) {
        return ApiResponse.ok(seatService.getSeats(id));
    }

    @PostMapping("/events/{id}/seats/hold")
    public ApiResponse<HoldResponse> hold(@PathVariable Long id,
                                          @AuthenticationPrincipal Long userId,
                                          @RequestBody HoldRequest request) {
        return ApiResponse.ok(seatService.hold(userId, id, request.seatIds(), request.queueToken()));
    }

    @DeleteMapping("/seats/hold/{holdId}")
    public ApiResponse<Void> release(@PathVariable Long holdId,
                                     @AuthenticationPrincipal Long userId) {
        seatService.release(holdId, userId);
        return ApiResponse.ok(null);
    }
}
