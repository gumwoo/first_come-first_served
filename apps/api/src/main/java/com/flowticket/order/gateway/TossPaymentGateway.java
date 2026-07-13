package com.flowticket.order.gateway;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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

    private static final String BASE_URL = "https://api.tosspayments.com";

    private final RestClient client;
    private final String authHeader;

    public TossPaymentGateway(@Value("${TOSS_SECRET_KEY:}") String secretKey) {
        this.client = RestClient.builder().baseUrl(BASE_URL).build();
        // Basic 인증: base64(secretKey + ":")
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    /** 서버 단독 승인은 Toss 카드 흐름에 없음 — 결제창 인증(confirm)이 필요. */
    @Override
    public ApproveResult approve(Long orderId, int amount, String method, String provider, String idempotencyKey) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR); // 결제창 인증(confirm) 경로를 사용하세요
    }

    @Override
    public String issueVbank(Long orderId, int amount) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR); // 무통장은 BE-5 범위 밖(웹훅 후속)
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
}
