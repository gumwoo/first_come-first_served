package com.flowticket.order.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Toss 어댑터가 실제로 보내는 요청을 검증한다.
 *
 * MockPaymentGateway는 금액을 보지 않고 성공을 돌려주므로, 기존 환불 테스트는 어댑터가 금액을
 * 빠뜨려도 통과한다. 이 테스트는 HTTP 본문을 직접 본다.
 */
class TossPaymentGatewayTest {

    private record Fixture(TossPaymentGateway gateway, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.tosspayments.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new TossPaymentGateway(builder.build(), "test-secret"), server);
    }

    @Test
    void refund_취소금액을_본문에_싣는다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("/v1/payments/PAY-1/cancel")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.cancelAmount").value(90_000))
                .andExpect(jsonPath("$.cancelReason").exists())
                .andRespond(withSuccess("""
                        {"status":"PARTIAL_CANCELED","paymentKey":"PAY-1"}
                        """, MediaType.APPLICATION_JSON));

        // 100,000원 결제를 수수료 10,000원 떼고 환불하는 경우(RefundPolicy tier1)
        ApproveResult res = f.gateway().refund("PAY-1", 90_000);

        f.server().verify();
        assertThat(res.success()).isTrue();
        assertThat(res.pgTid()).isEqualTo("PAY-1");
    }

    @Test
    void refund_전액이어도_금액을_명시한다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("/v1/payments/PAY-2/cancel")))
                .andExpect(jsonPath("$.cancelAmount").value(100_000))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","paymentKey":"PAY-2"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.gateway().refund("PAY-2", 100_000).success()).isTrue();
        f.server().verify();
    }

    /** 금액이 0 이하면 PG까지 보내지 않는다. 보냈다면 Mock 서버가 "기대하지 않은 요청"으로 실패한다. */
    @Test
    void refund_금액이_0이하면_호출하지_않는다() {
        Fixture f = fixture();

        ApproveResult res = f.gateway().refund("PAY-3", 0);

        assertThat(res.success()).isFalse();
        f.server().verify();
    }

    @Test
    void confirm_승인금액을_본문에_싣는다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("/v1/payments/confirm")))
                .andExpect(content().string(containsString("FLOWTICKET-ORDER-7")))
                .andExpect(jsonPath("$.amount").value(50_000))
                .andRespond(withSuccess("""
                        {"status":"DONE","paymentKey":"PAY-4"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.gateway().confirm(7L, "PAY-4", 50_000).success()).isTrue();
        f.server().verify();
    }
}
