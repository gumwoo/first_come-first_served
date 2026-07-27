package com.flowticket.alert.controller;

import com.flowticket.alert.dto.AlertSettingsResponse;
import com.flowticket.alert.dto.UpdateAlertRequest;
import com.flowticket.alert.service.AdminAlertService;
import com.flowticket.global.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 운영 알림 임계치(S07). /admin/** 은 SecurityConfig에서 ROLE_ADMIN 전용. */
@RestController
public class AdminAlertController {

    private final AdminAlertService adminAlertService;

    public AdminAlertController(AdminAlertService adminAlertService) {
        this.adminAlertService = adminAlertService;
    }

    @GetMapping("/admin/alerts")
    public ApiResponse<AlertSettingsResponse> get() {
        return ApiResponse.ok(adminAlertService.get());
    }

    @PutMapping("/admin/alerts")
    public ApiResponse<AlertSettingsResponse> update(@Valid @RequestBody UpdateAlertRequest request) {
        return ApiResponse.ok(adminAlertService.update(request.dlqPendingThreshold()));
    }
}
