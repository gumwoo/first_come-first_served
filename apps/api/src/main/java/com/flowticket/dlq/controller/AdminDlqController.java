package com.flowticket.dlq.controller;

import com.flowticket.dlq.dto.DlqMessageSummary;
import com.flowticket.dlq.service.AdminDlqService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영 DLQ 조회·재처리(S07 Phase 4c). /admin/** 은 SecurityConfig에서 ROLE_ADMIN 전용. */
@RestController
public class AdminDlqController {

    private final AdminDlqService adminDlqService;

    public AdminDlqController(AdminDlqService adminDlqService) {
        this.adminDlqService = adminDlqService;
    }

    @GetMapping("/admin/dlq")
    public ApiResponse<PageResponse<DlqMessageSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(adminDlqService.list(status, page, size));
    }

    @PostMapping("/admin/dlq/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable Long id) {
        adminDlqService.retry(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/admin/dlq/{id}/discard")
    public ApiResponse<Void> discard(@PathVariable Long id) {
        adminDlqService.discard(id);
        return ApiResponse.ok(null);
    }
}
