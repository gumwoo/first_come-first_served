package com.flowticket.order.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 취소 수수료 정책(S06). 공연일까지 남은 일수(D-day) 기준 기간별 수수료율(순수 함수, 테스트 가능).
 * 수치는 설정으로 외부화 → 테스트에서 고정. 당일·이후(D-0 이하)는 환불 불가.
 * 공연일 미상(startDate null)이면 시점 판단 불가 → 전액 환불(수수료 0)로 보수적 처리.
 */
@Component
public class RefundPolicy {

    private final int freeDays;   // 이 일수 이상 남으면 수수료 0
    private final int tier1Days;  // 이 일수 이상 남으면 tier1 수수료율
    private final int tier1Rate;  // %
    private final int tier2Rate;  // % (tier1 미만 ~ D-1)

    public RefundPolicy(
            @Value("${refund.free-days:8}") int freeDays,
            @Value("${refund.tier1-days:3}") int tier1Days,
            @Value("${refund.tier1-rate:10}") int tier1Rate,
            @Value("${refund.tier2-rate:30}") int tier2Rate) {
        this.freeDays = freeDays;
        this.tier1Days = tier1Days;
        this.tier1Rate = tier1Rate;
        this.tier2Rate = tier2Rate;
    }

    /** 환불 견적. eventDate 기준 now 시점의 수수료율·수수료·환불액·가능여부. */
    public RefundQuote quote(int paidAmount, LocalDate eventDate, LocalDateTime now) {
        if (eventDate == null) {
            return new RefundQuote(0, 0, paidAmount, true); // 시점 불명 → 전액
        }
        long daysUntil = ChronoUnit.DAYS.between(now.toLocalDate(), eventDate);
        if (daysUntil <= 0) {
            return new RefundQuote(0, 0, 0, false); // 당일·이후 → 환불 불가
        }
        int rate = daysUntil >= freeDays ? 0
                : daysUntil >= tier1Days ? tier1Rate
                : tier2Rate;
        int fee = paidAmount * rate / 100;
        return new RefundQuote(rate, fee, paidAmount - fee, true);
    }

    /** 환불 견적 결과. */
    public record RefundQuote(int feeRate, int fee, int refundAmount, boolean refundable) {}
}
