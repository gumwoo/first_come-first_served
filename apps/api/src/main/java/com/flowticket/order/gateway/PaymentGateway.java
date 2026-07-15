package com.flowticket.order.gateway;

/**
 * 결제 게이트웨이 포트(ADR-005). 외부 PG 의존을 인터페이스로 격리 —
 * 테스트/E2E는 Mock, 로컬/데모는 Toss 테스트 어댑터(BE-5)로 교체.
 */
public interface PaymentGateway {

    /** 카드/간편 즉시 승인. 성공 시 pgTid, 실패 시 사유. */
    ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey);

    /**
     * 무통장 — 가상계좌 발급(입금은 나중에 웹훅/트리거로 확인).
     * 반환: 계좌번호 + secret. secret은 입금 웹훅(DEPOSIT_CALLBACK) 위조 검증에 쓰인다(발급 시 저장).
     */
    VbankIssue issueVbank(Long orderId, int amount);

    /**
     * 결제창(클라이언트) 인증 후 서버 확정. Toss는 paymentKey로 승인 API를 호출한다.
     * Mock은 paymentKey를 무시하고 통과(테스트/데모용).
     */
    ApproveResult confirm(Long orderId, String paymentKey, int amount);

    /**
     * 환불(S06). 원 결제(pgTid)를 amount만큼 취소한다. Toss는 결제취소 API를 호출.
     * Mock은 pgTid를 무시하고 성공 반환(테스트/데모용).
     */
    ApproveResult refund(String pgTid, int amount);

    /** 가상계좌 발급 결과. account=입금 계좌번호, secret=입금 웹훅 검증용. */
    record VbankIssue(String account, String secret) {}

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
