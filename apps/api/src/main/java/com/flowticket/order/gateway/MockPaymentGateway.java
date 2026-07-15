package com.flowticket.order.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 테스트/E2E/CI용 결정론 Mock. 네트워크 없음.
 * 규칙: idempotencyKey가 "FAIL"로 시작하면 승인 거절, 그 외 성공. → 성공/실패 화면 모두 재현.
 * payment.gateway 미설정/mock 일 때 활성(기본).
 */
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.startsWith("FAIL")) {
            return ApproveResult.fail("승인 거절(mock)");
        }
        return ApproveResult.ok("MOCK-" + idempotencyKey);
    }

    @Override
    public VbankIssue issueVbank(Long orderId, int amount) {
        // secret은 발급 때 저장 → 입금 웹훅 검증에 사용. 결정론(테스트가 대조 가능).
        return new VbankIssue("MOCK-VBANK-" + orderId, "MOCK-SECRET-" + orderId);
    }

    @Override
    public ApproveResult confirm(Long orderId, String paymentKey, int amount) {
        if (paymentKey != null && paymentKey.startsWith("FAIL")) {
            return ApproveResult.fail("승인 거절(mock)");
        }
        return ApproveResult.ok("MOCK-CONFIRM-" + paymentKey);
    }

    @Override
    public ApproveResult refund(String pgTid, int amount) {
        return ApproveResult.ok("MOCK-REFUND-" + pgTid);
    }
}
