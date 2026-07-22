package com.flowticket.admin.controller;

import com.flowticket.admin.dto.AdminDashboardResponse;
import com.flowticket.admin.service.AdminDashboardService;
import com.flowticket.global.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영 대시보드(S07). /admin/** 은 SecurityConfig에서 ROLE_ADMIN 전용으로 게이트된다. */
@RestController
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/admin/dashboard")
    public ApiResponse<AdminDashboardResponse> dashboard() {
        return ApiResponse.ok(dashboardService.dashboard());
    }
}
