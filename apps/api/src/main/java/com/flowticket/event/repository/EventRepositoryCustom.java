package com.flowticket.event.repository;

import com.flowticket.event.dto.EventSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventRepositoryCustom {

    /**
     * 동적 필터(키워드/장르/상태/기간) + 페이징 검색.
     *
     * <p>엔티티가 아니라 요약 DTO를 돌려준다. 목록이 실제로 쓰는 컬럼은 9개인데
     * {@code selectFrom(event)}는 엔티티의 모든 컬럼(20개, KOPIS 상세 TEXT 포함)을 읽는다.
     *
     * <p>성능 개선 근거로 둔 것이 아니다 — {@code EXPLAIN (ANALYZE, BUFFERS)}에서 쿼리 형태별 차이는
     * 0.1~0.2ms였다. 쓰지 않는 데이터를 읽지 않기 위한 정리다.
     */
    Page<EventSummaryResponse> search(EventSearchCondition condition, Pageable pageable);
}
