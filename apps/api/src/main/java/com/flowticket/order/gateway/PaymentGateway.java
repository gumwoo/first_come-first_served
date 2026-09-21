package com.flowticket.order.gateway;

/**
 * 결제 게이트웨이 포트(ADR-005). 외부 PG 의존을 인터페이스로 격리:
 * 테스트/E2E는 Mock, 로컬/데모는 Toss 테스트 어댑터로 교체.
 */
public interface PaymentGateway {

    /** 카드/간편 즉시 승인. 성공 시 pgTid, 실패 시 사유. */
    ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey);

    /**
     * 무통장: 가상계좌 발급(입금은 나중에 웹훅/트리거로 확인).
     * 반환: 계좌번호 + secret. secret은 입금 웹훅(DEPOSIT_CALLBACK) 위조 검증에 쓰인다(발급 시 저장).
     */
    VbankIssue issueVbank(Long orderId, int amount);

    /**
     * 결제창(클라이언트) 인증 후 서버 확정. Toss는 paymentKey로 승인 API를 호출한다.
     * Mock은 paymentKey를 무시하고 통과(테스트/데모용).
     */
    ApproveResult confirm(Long orderId, String paymentKey, int amount);

    /**
     * 환불. 원 결제(pgTid)를 amount만큼 취소한다. Toss는 결제취소 API를 호출.
     * Mock은 pgTid를 무시하고 성공 반환(테스트/데모용).
     *
     * idempotencyKey는 PG까지 전달한다. 같은 취소가 두 번 나가는 경로가 실재하기 때문이다
     * (사용자 재시도, 보상 취소, 정산 잡). 키가 같으면 PG가 첫 결과를 돌려주므로 이중 취소가 없다.
     */
    ApproveResult refund(String pgTid, int amount, String idempotencyKey);

    /**
     * 주문 기준 승인 조회(정산, ADR-011). 우리 DB에 흔적이 없어도 PG에 승인이 남아 있는지 확인한다.
     * "승인 직후 크래시로 트랜잭션이 롤백된" 미아 승인을 찾는 유일한 경로. 조회 실패는 예외가 아니라
     * Inquiry.none()으로 보수적 처리(없는 걸 있다고 하지 않는다).
     */
    Inquiry inquire(Long orderId);

    /** 가상계좌 발급 결과. account=입금 계좌번호, secret=입금 웹훅 검증용. */
    record VbankIssue(String account, String secret) {}

    /**
     * PG가 보는 결제 상태.
     *
     * 이 다섯을 구분하는 것이 정산의 전제다. 예전에는 "승인 없음"·"이미 취소됨"·"조회 실패"가
     * 모두 한 값이었는데, 그 상태로 환불 정산을 붙이면 PG 조회가 잠깐 죽은 동안 멀쩡한 주문을
     * 환불 처리한다. 모르는 것(UNKNOWN)과 아는 것을 절대 같이 다루지 않는다.
     */
    enum PgStatus {
        /** 승인이 유효하게 남아 있다. */
        DONE,
        /** 전액 취소됐다. */
        CANCELED,
        /** 일부만 취소됐다(수수료를 뗀 환불이 여기로 온다). */
        PARTIAL_CANCELED,
        /** PG에 이 주문의 결제가 없다. 승인 자체가 나지 않았다는 뜻. */
        NOT_FOUND,
        /** 조회하지 못했다(네트워크·5xx·해석 불가). 아무 판단도 하면 안 된다. */
        UNKNOWN
    }

    /**
     * 승인 조회 결과. canceledAmount는 PG가 실제로 취소한 금액이고, 취소 상태가 아니면 0이다.
     * 우리 정책으로 다시 계산하지 않는다. 환불 시점의 정책을 사후에 복원할 수 없기 때문이다.
     */
    record Inquiry(PgStatus status, String pgTid, int canceledAmount) {

        /** PG에 결제 기록이 없음. */
        public static Inquiry none() {
            return new Inquiry(PgStatus.NOT_FOUND, null, 0);
        }

        /** 조회 실패. 정산은 이 값을 보면 아무것도 하지 않는다. */
        public static Inquiry unknown() {
            return new Inquiry(PgStatus.UNKNOWN, null, 0);
        }

        public static Inquiry approved(String pgTid) {
            return new Inquiry(PgStatus.DONE, pgTid, 0);
        }

        public static Inquiry canceled(String pgTid, int canceledAmount, boolean partial) {
            return new Inquiry(partial ? PgStatus.PARTIAL_CANCELED : PgStatus.CANCELED,
                    pgTid, canceledAmount);
        }

        /** 유효한 승인이 남아 있다(미아 승인 정산의 조건). */
        public boolean approved() {
            return status == PgStatus.DONE;
        }

        /** 취소가 남아 있다(환불 정산의 조건). 조회 실패는 여기 포함되지 않는다. */
        public boolean canceled() {
            return status == PgStatus.CANCELED || status == PgStatus.PARTIAL_CANCELED;
        }
    }

    /**
     * 승인·취소 요청의 결과.
     *
     * 실패를 둘로 가른다. 예전에는 타임아웃·5xx까지 전부 "실패"라 호출자가 "PG가 하지 않았다"로
     * 읽었는데, 실제로는 **했는데 응답만 못 받은 경우**가 섞여 있었다. 그 상태에서 되돌리기를
     * 실행하면 돈은 움직였는데 우리 장부만 원래대로 돌아간다(Inquiry.PgStatus와 같은 이유).
     */
    enum PgOutcome {
        /** PG가 처리했다. */
        SUCCESS,
        /** PG가 답을 줬고, 그 답이 "하지 않았다"이다. */
        REJECTED,
        /** 결과를 모른다(타임아웃·네트워크·5xx·해석 불가). */
        UNKNOWN
    }

    /** 승인 결과. */
    record ApproveResult(PgOutcome outcome, String pgTid, String failReason) {

        public static ApproveResult ok(String pgTid) {
            return new ApproveResult(PgOutcome.SUCCESS, pgTid, null);
        }

        /** PG가 답을 줬고, 그 답이 "하지 않았다"이다. */
        public static ApproveResult fail(String reason) {
            return new ApproveResult(PgOutcome.REJECTED, null, reason);
        }

        /** 결과를 모른다. 되돌리지도 확정하지도 말고 정산에 넘겨야 한다. */
        public static ApproveResult unknown(String reason) {
            return new ApproveResult(PgOutcome.UNKNOWN, null, reason);
        }

        public boolean success() {
            return outcome == PgOutcome.SUCCESS;
        }

        /** 모르는 결과인가. 호출자가 보상·되돌리기를 결정하기 전에 반드시 봐야 한다. */
        public boolean unknown() {
            return outcome == PgOutcome.UNKNOWN;
        }
    }
}
