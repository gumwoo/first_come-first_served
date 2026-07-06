package com.flowticket.seat.service;

import com.flowticket.seat.domain.SeatGrade;

/**
 * 가격 티어. base = A석 가격, 등급 배수 고정(A=1.0/S=1.3/R=1.6/VIP=2.0).
 * 장르 기반 매핑 + eventId fallback으로 공연별 가격을 결정(priceText 파싱 안 함, ADR-004).
 */
enum PriceTier {
    LOW(30000),
    MID(60000),
    HIGH(90000);

    private final int base;

    PriceTier(int base) {
        this.base = base;
    }

    int priceOf(SeatGrade grade) {
        double m = switch (grade) {
            case A -> 1.0;
            case S -> 1.3;
            case R -> 1.6;
            case VIP -> 2.0;
        };
        return (int) Math.round(base * m);
    }

    /** KOPIS genrenm 정확 문자열 매핑, 미매핑은 eventId 기반 안정 선택. */
    static PriceTier of(String genre, long eventId) {
        if (genre != null) {
            if (genre.contains("대중음악") || genre.contains("뮤지컬")) {
                return HIGH;
            }
            if (genre.contains("클래식") || genre.contains("무용")) {
                return MID;
            }
            if (genre.contains("연극") || genre.contains("국악")) {
                return LOW;
            }
        }
        return switch ((int) Math.floorMod(eventId, 3)) {
            case 0 -> LOW;
            case 1 -> MID;
            default -> HIGH;
        };
    }
}
