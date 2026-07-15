package com.flowticket.order.controller;

import com.flowticket.global.common.ApiResponse;
import com.flowticket.global.common.PageResponse;
import com.flowticket.order.dto.MyOrderDetail;
import com.flowticket.order.dto.MyOrderSummary;
import com.flowticket.order.dto.RefundRequest;
import com.flowticket.order.dto.RefundResponse;
import com.flowticket.order.service.MyOrderService;
import com.flowticket.order.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 예매 조회·취소(S06, 회원 본인). 목록 탭 필터 + 페이징, 상세·환불은 소유자 검증. */
@RestController
public class MyOrderController {

    private final MyOrderService myOrderService;
    private final RefundService refundService;

    public MyOrderController(MyOrderService myOrderService, RefundService refundService) {
        this.myOrderService = myOrderService;
        this.refundService = refundService;
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

    /** 예매 취소·환불. PAID + 환불 가능 시점에서만(그 외 REFUND_NOT_ALLOWED). 멱등. */
    @PostMapping("/me/orders/{id}/refund")
    public ApiResponse<RefundResponse> refund(@AuthenticationPrincipal Long userId,
                                              @PathVariable Long id,
                                              @Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(refundService.refund(userId, id, request.reason(), request.idempotencyKey()));
    }
}
