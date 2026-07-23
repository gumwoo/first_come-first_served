package com.flowticket.admin.controller;

import com.flowticket.admin.dto.AdminEventDetail;
import com.flowticket.admin.dto.AdminEventSummary;
import com.flowticket.admin.dto.CreateEventRequest;
import com.flowticket.admin.dto.UpdateEventRequest;
import com.flowticket.admin.service.AdminEventService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영 이벤트 관리(S07). /admin/** 은 SecurityConfig에서 ROLE_ADMIN 전용으로 게이트된다. */
@RestController
public class AdminEventController {

    private final AdminEventService adminEventService;

    public AdminEventController(AdminEventService adminEventService) {
        this.adminEventService = adminEventService;
    }

    @GetMapping("/admin/events")
    public ApiResponse<PageResponse<AdminEventSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminEventService.list(page, size));
    }

    @GetMapping("/admin/events/{id}")
    public ApiResponse<AdminEventDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(adminEventService.detail(id));
    }

    @PostMapping("/admin/events")
    public ApiResponse<AdminEventDetail> create(@Valid @RequestBody CreateEventRequest request) {
        return ApiResponse.ok(adminEventService.create(request));
    }

    @PatchMapping("/admin/events/{id}")
    public ApiResponse<AdminEventDetail> update(@PathVariable Long id,
                                                @RequestBody UpdateEventRequest request) {
        return ApiResponse.ok(adminEventService.update(id, request));
    }
}
