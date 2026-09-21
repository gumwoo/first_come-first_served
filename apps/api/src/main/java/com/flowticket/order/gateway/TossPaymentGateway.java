package com.flowticket.order.gateway;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Toss Payments 테스트 어댑터(ADR-005). payment.gateway=toss 일 때 활성.
 * 카드는 결제창(클라이언트) 인증으로 받은 paymentKey를 승인 API로 확정한다.
 * 비밀키(TOSS_SECRET_KEY)는 환경변수로만 주입.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "toss")
public class TossPaymentGateway implements PaymentGateway {

    private final RestClient client;
    private final String authHeader;

    /** 타임아웃이 걸린 RestClient는 TossClientConfig가 만든다(TS-028). */
    public TossPaymentGateway(@Qualifier("tossClient") RestClient tossClient,
                              @Value("${TOSS_SECRET_KEY:}") String secretKey) {
        this.client = tossClient;
        // Basic 인증: base64(secretKey + ":")
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    /** 서버 단독 승인은 Toss 카드 흐름에 없음: 결제창 인증(confirm)이 필요. */
    @Override
    public ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR); // 결제창 인증(confirm) 경로를 사용하세요
    }

    @Override
    public VbankIssue issueVbank(Long orderId, int amount) {
        // 실 Toss 가상계좌는 결제창 발급 흐름이 필요. 데모는 Mock 경로 사용(웹훅 검증 로직은 동일 규약).
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    /**
     * 원 결제(paymentKey=pgTid)를 amount만큼 취소. Toss 결제취소 API. cancelReason 필수.
     *
     * cancelAmount를 빼면 Toss는 전액 취소로 처리한다. 수수료를 뗀 환불(RefundPolicy)에서 그 값을
     * 생략하면 DB에는 수수료 차감액이, PG에는 전액 취소가 남아 장부가 어긋난다.
     */
    @Override
    public ApproveResult refund(String pgTid, int amount, String idempotencyKey) {
        if (pgTid == null || pgTid.isBlank()) {
            return ApproveResult.fail("환불 대상 결제 없음");
        }
        if (amount <= 0) {
            // 여기서 막지 않으면 Toss가 거절한 뒤 실패로 수렴하지만, 취소 금액이 0인 요청은
            // 호출 쪽 계산이 깨진 것이라 PG까지 보내지 않는다.
            return ApproveResult.fail("취소 금액이 0 이하");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = client.post()
                    .uri("/v1/payments/{paymentKey}/cancel", pgTid)
                    .header("Authorization", authHeader)
                    .headers(h -> {
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            h.set("Idempotency-Key", idempotencyKey);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancelReason", "고객 취소", "cancelAmount", amount))
                    .retrieve()
                    .body(Map.class);
            String status = res == null ? null : String.valueOf(res.get("status"));
            if (res != null && ("CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status))) {
                return ApproveResult.ok(String.valueOf(res.get("paymentKey")));
            }
            return ApproveResult.fail("토스 취소 상태: " + status);
        } catch (Exception e) {
            log.warn("[toss] refund 실패 pgTid={}: {}", pgTid, e.getMessage());
            return ApproveResult.fail("토스 취소 실패");
        }
    }

    /** 결제창에서 받은 paymentKey로 Toss 승인 API 호출. orderId는 FE와 동일 규약으로 파생. */
    @Override
    public ApproveResult confirm(Long orderId, String paymentKey, int amount) {
        String tossOrderId = "FLOWTICKET-ORDER-" + orderId;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = client.post()
                    .uri("/v1/payments/confirm")
                    .header("Authorization", authHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentKey", paymentKey, "orderId", tossOrderId, "amount", amount))
                    .retrieve()
                    .body(Map.class);
            String status = res == null ? null : String.valueOf(res.get("status"));
            if (res != null && "DONE".equals(status)) {
                return ApproveResult.ok(String.valueOf(res.get("paymentKey")));
            }
            return ApproveResult.fail("토스 승인 상태: " + status);
        } catch (Exception e) {
            log.warn("[toss] confirm 실패 order={}: {}", orderId, e.getMessage());
            return ApproveResult.fail("토스 승인 실패");
        }
    }

    /**
     * 주문번호로 결제 조회(정산). Toss는 우리 orderId 규약으로 조회를 지원한다.
     *
     * 404만 "결제 없음"이다. 그 외의 실패는 UNKNOWN으로 올려 정산이 판단을 멈추게 한다.
     * 예전에는 모든 실패를 none()으로 뭉갰는데, 그 값으로는 "승인이 없다"와 "모른다"가 구분되지
     * 않아 조회 장애가 곧 오판이 된다.
     */
    @Override
    public Inquiry inquire(Long orderId) {
        String tossOrderId = "FLOWTICKET-ORDER-" + orderId;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = client.get()
                    .uri("/v1/payments/orders/{orderId}", tossOrderId)
                    .header("Authorization", authHeader)
                    .retrieve()
                    .body(Map.class);
            if (res == null) {
                return Inquiry.unknown();
            }
            String status = String.valueOf(res.get("status"));
            String paymentKey = String.valueOf(res.get("paymentKey"));
            if ("DONE".equals(status)) {
                return Inquiry.approved(paymentKey);
            }
            if ("CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status)) {
                return Inquiry.canceled(paymentKey, canceledAmount(res), "PARTIAL_CANCELED".equals(status));
            }
            // READY·IN_PROGRESS·WAITING_FOR_DEPOSIT: 아직 승인도 취소도 아니다.
            // ABORTED·EXPIRED: 승인이 나지 않고 끝났다. 어느 쪽도 정산이 손댈 상태가 아니다.
            return Inquiry.none();
        } catch (HttpClientErrorException.NotFound e) {
            return Inquiry.none(); // PG에 이 주문의 결제가 없다
        } catch (Exception e) {
            log.warn("[toss] 결제 조회 실패 order={}: {}", orderId, e.getMessage());
            return Inquiry.unknown();
        }
    }

    /**
     * 실제 취소 금액. 취소 이력(cancels[])의 합을 쓴다.
     * 잔액(balanceAmount)으로 역산하지 않는 것은 그 값이 부분취소·부분환불에서 의미가 갈리기 때문이다.
     */
    private static int canceledAmount(Map<String, Object> res) {
        Object cancels = res.get("cancels");
        if (!(cancels instanceof List<?> list)) {
            return 0;
        }
        int sum = 0;
        for (Object row : list) {
            if (row instanceof Map<?, ?> m && m.get("cancelAmount") instanceof Number n) {
                sum += n.intValue();
            }
        }
        return sum;
    }
}
