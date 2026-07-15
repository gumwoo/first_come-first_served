package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageResponse;
import com.flowticket.order.dto.MyOrderDetail;
import com.flowticket.order.dto.MyOrderSummary;
import com.flowticket.order.service.MyOrderService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 예매 조회(S06, 회원 본인). 목록 탭 필터 + 페이징, 상세는 소유자 검증. */
@RestController
public class MyOrderController {

    private final MyOrderService myOrderService;

    public MyOrderController(MyOrderService myOrderService) {
        this.myOrderService = myOrderService;
    }

    @GetMapping("/me/orders")
    public ApiResponse<PageResponse<MyOrderSummary>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(myOrderService.list(userId, status, page, size));
    }

    @GetMapping("/me/orders/{id}")
    public ApiResponse<MyOrderDetail> detail(@AuthenticationPrincipal Long userId,
                                             @PathVariable Long id) {
        return ApiResponse.ok(myOrderService.detail(userId, id));
    }
}
