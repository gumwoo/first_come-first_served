package com.flowticket.queue.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.queue.dto.QueueStatusResponse;
import com.flowticket.queue.dto.QueueTokenResponse;
import com.flowticket.queue.service.QueueService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 대기열 진입/상태. 진입은 회원(Bearer), 상태는 토큰으로 조회. 입출력/매핑만. */
@RestController
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    /** 대기 진입 → 토큰 발급(회원). */
    @PostMapping("/events/{id}/queue/token")
    public ApiResponse<QueueTokenResponse> enter(@PathVariable Long id,
                                                 @AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(queueService.issue(userId, id));
    }

    /** 대기 상태 폴링(토큰). SSE 미지원/끊김 대비 폴백. */
    @GetMapping("/queue/status")
    public ApiResponse<QueueStatusResponse> status(@RequestParam String token) {
        return ApiResponse.ok(queueService.status(token));
    }
}
