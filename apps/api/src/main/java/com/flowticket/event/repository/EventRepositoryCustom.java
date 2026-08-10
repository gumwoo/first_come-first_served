package com.flowticket.event.repository;

import com.flowticket.event.dto.EventSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventRepositoryCustom {

    /**
     * 동적 필터(키워드/장르/상태/기간) + 페이징 검색.
     *
     * <p><b>엔티티가 아니라 요약 DTO를 돌려준다.</b> 목록이 실제로 쓰는 컬럼은 9개인데
     * {@code selectFrom(event)}는 엔티티의 <b>모든 컬럼(20개)</b>을 읽는다. V16에서 KOPIS 상세
     * 필드(TEXT 4개)가 추가되면서 그 폭이 더 넓어졌다.
     *
     * <p><b>성능 회귀를 고치려는 변경이 아니다.</b> 목록 지연이 늘어난 관측이 있었지만,
     * {@code EXPLAIN (ANALYZE, BUFFERS)}로 확인해보니 세 가지 쿼리 형태(구엔티티 15컬럼 / 신엔티티
     * 20컬럼 / 프로젝션 9컬럼)가 모두 <b>0.1~0.2ms에 전부 캐시 히트</b>였다. 즉 <b>이 컬럼들이 그
     * 지연의 원인이라는 근거는 없다.</b> 그럼에도 쓰지 않는 데이터를 읽지 않는 것이 옳으므로
     * 쿼리 형태만 정리한다.
     */
    Page<EventSummaryResponse> search(EventSearchCondition condition, Pageable pageable);
}
