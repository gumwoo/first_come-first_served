package com.flowticket.event.repository;

import com.flowticket.event.domain.EventStatus;
import java.time.LocalDate;

/** 공연 목록/검색 동적 필터 조건(전부 선택). */
public record EventSearchCondition(
        String keyword,      // 제목 contains
        String genre,
        String region,       // 지역 contains (KOPIS area 시도명, 예 "서울"→"서울특별시")
        EventStatus status,
        LocalDate from,      // start_date >= from
        LocalDate to         // start_date <= to
) {}
