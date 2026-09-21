package com.flowticket.order.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flowticket.order.gateway.PaymentGateway.ApproveResult;
import com.flowticket.order.gateway.PaymentGateway.Inquiry;
import com.flowticket.order.gateway.PaymentGateway.PgStatus;
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
        ApproveResult res = f.gateway().refund("PAY-1", 90_000, "REFUND-KEY-1");

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

        assertThat(f.gateway().refund("PAY-2", 100_000, null).success()).isTrue();
        f.server().verify();
    }

    /** 금액이 0 이하면 PG까지 보내지 않는다. 보냈다면 Mock 서버가 "기대하지 않은 요청"으로 실패한다. */
    @Test
    void refund_금액이_0이하면_호출하지_않는다() {
        Fixture f = fixture();

        ApproveResult res = f.gateway().refund("PAY-3", 0, "REFUND-KEY-3");

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

    @Test
    void refund_멱등키를_헤더로_보낸다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("/v1/payments/PAY-5/cancel")))
                .andExpect(header("Idempotency-Key", "R-42"))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","paymentKey":"PAY-5"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.gateway().refund("PAY-5", 10_000, "R-42").success()).isTrue();
        f.server().verify();
    }

    /** 키가 없으면 헤더를 만들지 않는다. 빈 키를 보내면 PG가 그 값으로 요청을 묶을 수 있다. */
    @Test
    void refund_멱등키가_없으면_헤더를_붙이지_않는다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("/v1/payments/PAY-6/cancel")))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","paymentKey":"PAY-6"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(f.gateway().refund("PAY-6", 10_000, "  ").success()).isTrue();
        f.server().verify();
    }

    @Test
    void inquire_승인은_DONE이다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("/v1/payments/orders/FLOWTICKET-ORDER-1")))
                .andRespond(withSuccess("""
                        {"status":"DONE","paymentKey":"PAY-7"}
                        """, MediaType.APPLICATION_JSON));

        Inquiry inq = f.gateway().inquire(1L);

        assertThat(inq.status()).isEqualTo(PgStatus.DONE);
        assertThat(inq.approved()).isTrue();
        assertThat(inq.canceled()).isFalse();
        assertThat(inq.pgTid()).isEqualTo("PAY-7");
    }

    /** 수수료를 뗀 환불은 부분 취소로 남는다. 취소 이력의 합이 실제로 환불된 금액이다. */
    @Test
    void inquire_부분취소는_취소금액을_함께_돌려준다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("FLOWTICKET-ORDER-2")))
                .andRespond(withSuccess("""
                        {"status":"PARTIAL_CANCELED","paymentKey":"PAY-8",
                         "cancels":[{"cancelAmount":90000,"transactionKey":"T1"}]}
                        """, MediaType.APPLICATION_JSON));

        Inquiry inq = f.gateway().inquire(2L);

        assertThat(inq.status()).isEqualTo(PgStatus.PARTIAL_CANCELED);
        assertThat(inq.canceled()).isTrue();
        assertThat(inq.canceledAmount()).isEqualTo(90_000);
    }

    @Test
    void inquire_전액취소는_취소이력의_합을_쓴다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("FLOWTICKET-ORDER-3")))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","paymentKey":"PAY-9",
                         "cancels":[{"cancelAmount":40000},{"cancelAmount":60000}]}
                        """, MediaType.APPLICATION_JSON));

        Inquiry inq = f.gateway().inquire(3L);

        assertThat(inq.status()).isEqualTo(PgStatus.CANCELED);
        assertThat(inq.canceledAmount()).isEqualTo(100_000);
    }

    /** 404만 "결제 없음"이다. 승인이 난 적 없는 주문이 여기로 온다. */
    @Test
    void inquire_404는_NOT_FOUND다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("FLOWTICKET-ORDER-4")))
                .andRespond(withResourceNotFound());

        Inquiry inq = f.gateway().inquire(4L);

        assertThat(inq.status()).isEqualTo(PgStatus.NOT_FOUND);
        assertThat(inq.approved()).isFalse();
        assertThat(inq.canceled()).isFalse();
    }

    /**
     * 조회 장애는 UNKNOWN이다. 이걸 NOT_FOUND로 뭉개면 정산이 "취소도 승인도 없다"고 읽어
     * 멀쩡한 결제를 손대게 된다.
     */
    @Test
    void inquire_조회장애는_UNKNOWN이다() {
        Fixture f = fixture();
        f.server().expect(requestTo(containsString("FLOWTICKET-ORDER-5")))
                .andRespond(withServerError());

        Inquiry inq = f.gateway().inquire(5L);

        assertThat(inq.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(inq.approved()).isFalse();
        assertThat(inq.canceled()).isFalse();
    }
}
