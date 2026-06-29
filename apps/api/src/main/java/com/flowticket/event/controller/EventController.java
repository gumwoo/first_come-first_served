package com.flowticket.event.controller;

import com.flowticket.event.dto.EventDetailResponse;
import com.flowticket.event.dto.EventSummaryResponse;
import com.flowticket.event.service.EventService;
import com.flowticket.event.service.RankingService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공연 조회(목록/인기/실시간/상세/검색). 비로그인 열람 가능. 입출력/매핑만(변환·조회는 service). */
@RestController
public class EventController {

    private static final int KEYWORDS_SIZE = 10;

    private final EventService eventService;
    private final RankingService rankingService;

    public EventController(EventService eventService, RankingService rankingService) {
        this.eventService = eventService;
        this.rankingService = rankingService;
    }

    @GetMapping("/events")
    public ApiResponse<PageResponse<EventSummaryResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(eventService.list(genre, status, from, to, page, size));
    }

    @GetMapping("/events/popular")
    public ApiResponse<List<EventSummaryResponse>> popular() {
        return ApiResponse.ok(eventService.popular());
    }

    @GetMapping("/events/ranking/realtime")
    public ApiResponse<List<EventSummaryResponse>> realtimeRanking() {
        return ApiResponse.ok(eventService.realtimeRanking());
    }

    @GetMapping("/events/{id}")
    public ApiResponse<EventDetailResponse> detail(@PathVariable Long id, HttpServletRequest request) {
        rankingService.recordView(id, clientIp(request)); // 조회수 기록(best-effort)
        return ApiResponse.ok(eventService.detail(id));
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<EventSummaryResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(eventService.searchByKeyword(q, genre, status, page, size));
    }

    @GetMapping("/search/popular-keywords")
    public ApiResponse<List<String>> popularKeywords() {
        return ApiResponse.ok(rankingService.topKeywords(KEYWORDS_SIZE));
    }

    /** 프록시 뒤 실제 클라이언트 IP. X-Forwarded-For 우선(첫 값), 없으면 remoteAddr. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
