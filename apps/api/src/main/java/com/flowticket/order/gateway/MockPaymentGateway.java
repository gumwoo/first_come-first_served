package com.flowticket.order.gateway;

import org.springframework.stereotype.Component;

/**
 * 테스트/E2E/CI용 결정론 Mock. 네트워크 없음.
 * 규칙: idempotencyKey가 "FAIL"로 시작하면 승인 거절, 그 외 성공. → 성공/실패 화면 모두 재현.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.startsWith("FAIL")) {
            return ApproveResult.fail("승인 거절(mock)");
        }
        return ApproveResult.ok("MOCK-" + idempotencyKey);
    }
}
