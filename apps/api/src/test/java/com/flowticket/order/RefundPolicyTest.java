package com.flowticket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowticket.order.service.RefundPolicy;
import com.flowticket.order.service.RefundPolicy.RefundQuote;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 취소 수수료 정책 순수 함수(S06). 기간별 수수료율 경계값 검증(네트워크·DB 없음).
 * 정책: D-8↑ 0% / D-3~7 10% / D-1~2 30% / 당일·이후 환불 불가.
 */
class RefundPolicyTest {

    private final RefundPolicy policy = new RefundPolicy(8, 3, 10, 30);
    private final LocalDateTime now = LocalDateTime.of(2026, 7, 15, 10, 0);

    @Test
    void 공연일_미상이면_전액_환불() {
        RefundQuote q = policy.quote(30000, null, now);
        assertThat(q.refundable()).isTrue();
        assertThat(q.fee()).isZero();
        assertThat(q.refundAmount()).isEqualTo(30000);
    }

    @Test
    void D8_이상이면_수수료_0() {
        RefundQuote q = policy.quote(30000, now.toLocalDate().plusDays(10), now);
        assertThat(q.feeRate()).isZero();
        assertThat(q.refundAmount()).isEqualTo(30000);
    }

    @Test
    void D3에서_D7사이면_10퍼센트() {
        RefundQuote q = policy.quote(30000, now.toLocalDate().plusDays(5), now);
        assertThat(q.feeRate()).isEqualTo(10);
        assertThat(q.fee()).isEqualTo(3000);
        assertThat(q.refundAmount()).isEqualTo(27000);
    }

    @Test
    void D1에서_D2사이면_30퍼센트() {
        RefundQuote q = policy.quote(30000, now.toLocalDate().plusDays(2), now);
        assertThat(q.feeRate()).isEqualTo(30);
        assertThat(q.fee()).isEqualTo(9000);
        assertThat(q.refundAmount()).isEqualTo(21000);
    }

    @Test
    void 당일이면_환불_불가() {
        RefundQuote q = policy.quote(30000, now.toLocalDate(), now);
        assertThat(q.refundable()).isFalse();
    }

    @Test
    void 공연일_지났으면_환불_불가() {
        RefundQuote q = policy.quote(30000, now.toLocalDate().minusDays(1), now);
        assertThat(q.refundable()).isFalse();
    }
}
