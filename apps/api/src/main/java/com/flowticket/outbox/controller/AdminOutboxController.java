package com.flowticket.outbox.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageQuery;
import com.flowticket.global.common.PageResponse;
import jakarta.validation.Valid;
import com.flowticket.outbox.dto.OutboxEventSummary;
import com.flowticket.outbox.service.AdminOutboxService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영 아웃박스 조회·재발행·폐기(S08). /admin/** 은 SecurityConfig에서 ROLE_ADMIN 전용. */
@RestController
public class AdminOutboxController {

    private final AdminOutboxService adminOutboxService;

    public AdminOutboxController(AdminOutboxService adminOutboxService) {
        this.adminOutboxService = adminOutboxService;
    }

    @GetMapping("/admin/outbox")
    public ApiResponse<PageResponse<OutboxEventSummary>> list(
            @RequestParam(required = false) String status,
            @Valid @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(adminOutboxService.list(status, pageQuery.page(), pageQuery.size()));
    }

    @PostMapping("/admin/outbox/{id}/requeue")
    public ApiResponse<Void> requeue(@PathVariable UUID id) {
        adminOutboxService.requeue(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/admin/outbox/{id}/discard")
    public ApiResponse<Void> discard(@PathVariable UUID id) {
        adminOutboxService.discard(id);
        return ApiResponse.ok(null);
    }
}
