package com.flowticket.order.gateway;

import com.flowticket.global.error.BusinessException;
import com.flowticket.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
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

    /**
     * ⚠️ <b>타임아웃을 반드시 건다.</b> 이전에는 {@code RestClient.builder().baseUrl(..).build()}
     * 였는데, request factory를 주지 않으면 RestClient는 클래스패스에서 고르고 이 이미지에는
     * Apache HttpClient5·Jetty·OkHttp가 없어 <b>JDK HttpClient로 떨어진다. 그쪽은 connect/read
     * 기본 타임아웃이 없다</b>({@link com.flowticket.event.kopis.KopisClientConfig}가 같은 사실을
     * 이미 적어 두었다 — KOPIS만 지키고 결제는 안 지키고 있었다).
     *
     * <p>결제는 KOPIS보다 나쁘다. 이 호출은 <b>DB 트랜잭션 안</b>에서 일어나므로(PaymentService·
     * RefundService), 응답 없는 PG 하나가 톰캣 스레드와 <b>Hikari 커넥션을 함께</b> 묶는다.
     * 풀은 파드당 5다({@code DB_POOL_MAX}, TS-021) — 느린 결제 5건이면 그 파드의 DB가 멎는다.
     *
     * <p><b>read 타임아웃이 새 실패 유형을 만든다는 점을 알고 고른 값이다.</b> 타임아웃은
     * "승인 안 됨"이 아니라 <b>"모름"</b>이다 — Toss는 승인했는데 우리가 못 받았을 수 있고,
     * 그러면 롤백돼 DB에 흔적이 없는 <b>미아 승인</b>이 된다. 그 클래스를 회수하려고 만든 것이
     * ADR-011 정산 잡이라, 이 타임아웃은 <b>그 장치가 있기 때문에</b> 안전하다.
     * 반대로 값이 짧으면 멀쩡한 결제를 미아로 만들므로 넉넉히 준다(기본 10초).
     */
    public TossPaymentGateway(RestClient.Builder builder,
                              @Value("${TOSS_SECRET_KEY:}") String secretKey,
                              @Value("${toss.connect-timeout-ms:2000}") long connectTimeoutMs,
                              @Value("${toss.read-timeout-ms:10000}") long readTimeoutMs) {
        this.client = builder.clone().baseUrl(BASE_URL)
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                        .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
                .build();
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
    public VbankIssue issueVbank(Long orderId, int amount) {
        // 실 Toss 가상계좌는 결제창 발급 흐름이 필요 — 데모는 Mock 경로 사용(웹훅 검증 로직은 동일 규약).
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    /** 원 결제(paymentKey=pgTid)를 취소. Toss 결제취소 API. cancelReason 필수. */
    @Override
    public ApproveResult refund(String pgTid, int amount) {
        if (pgTid == null || pgTid.isBlank()) {
            return ApproveResult.fail("환불 대상 결제 없음");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = client.post()
                    .uri("/v1/payments/{paymentKey}/cancel", pgTid)
                    .header("Authorization", authHeader)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancelReason", "고객 취소"))
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
     * 승인 상태(DONE)만 "미아 승인 후보"로 본다 — 이미 취소(CANCELED)면 정산 대상이 아니다.
     * 조회 실패(404 포함)는 예외로 올리지 않고 <b>없음</b>으로 처리한다(없는 승인을 만들어내지 않기 위해).
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
            if (res != null && "DONE".equals(String.valueOf(res.get("status")))) {
                return Inquiry.approved(String.valueOf(res.get("paymentKey")));
            }
            return Inquiry.none();
        } catch (Exception e) {
            log.warn("[toss] 결제 조회 실패 order={}: {}", orderId, e.getMessage());
            return Inquiry.none();
        }
    }
}
