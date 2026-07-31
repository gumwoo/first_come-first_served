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

    /**
     * Mock은 "승인 없음"을 반환한다 — 정산 잡이 평시(정상 주문 만료 등)에 아무것도 취소하지 않도록.
     * 미아 승인 시나리오는 테스트가 이 메서드를 스텁해 재현한다(결정론 유지).
     */
    @Override
    public Inquiry inquire(Long orderId) {
        return Inquiry.none();
    }
}
