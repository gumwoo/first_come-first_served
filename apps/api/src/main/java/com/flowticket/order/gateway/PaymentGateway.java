package com.flowticket.order.gateway;

/**
 * 결제 게이트웨이 포트(ADR-005). 외부 PG 의존을 인터페이스로 격리 —
 * 테스트/E2E는 Mock, 로컬/데모는 Toss 테스트 어댑터(BE-5)로 교체.
 */
public interface PaymentGateway {

    /** 카드/간편 즉시 승인. 성공 시 pgTid, 실패 시 사유. */
    ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey);

    /** 승인 결과. */
    record ApproveResult(boolean success, String pgTid, String failReason) {
        public static ApproveResult ok(String pgTid) {
            return new ApproveResult(true, pgTid, null);
        }

        public static ApproveResult fail(String reason) {
            return new ApproveResult(false, null, reason);
        }
    }
}
