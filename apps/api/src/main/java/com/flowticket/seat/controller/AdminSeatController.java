package com.flowticket.seat.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.seat.service.SeatSeeder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 좌석 생성 수동 트리거(누락/재시드용 보조). 멱등(있으면 skip). 기본 경로는 sync 자동 시딩. */
@RestController
public class AdminSeatController {

    private final SeatSeeder seatSeeder;

    public AdminSeatController(SeatSeeder seatSeeder) {
        this.seatSeeder = seatSeeder;
    }

    @PostMapping("/admin/events/{id}/seats")
    public ApiResponse<Void> generate(@PathVariable Long id) {
        seatSeeder.seedForEvent(id);
        return ApiResponse.ok(null);
    }
}
