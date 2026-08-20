package com.flowticket.admin.controller;

import com.flowticket.admin.dto.AdminOrderSummary;
import com.flowticket.admin.service.AdminOrderService;
import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageQuery;
import com.flowticket.global.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영 주문 조회(S07). /admin/** 은 SecurityConfig에서 ROLE_ADMIN 전용으로 게이트된다. */
@RestController
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    @GetMapping("/admin/orders")
    public ApiResponse<PageResponse<AdminOrderSummary>> list(
            @RequestParam(required = false) String status,
            @Valid @ModelAttribute PageQuery pageQuery) {
        return ApiResponse.ok(adminOrderService.list(status, pageQuery.page(), pageQuery.size()));
    }
}
