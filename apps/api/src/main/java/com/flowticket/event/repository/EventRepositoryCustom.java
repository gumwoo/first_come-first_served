package com.flowticket.event.repository;

import com.flowticket.event.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventRepositoryCustom {

    /** 동적 필터(키워드/장르/상태/기간) + 페이징 검색. */
    Page<Event> search(EventSearchCondition condition, Pageable pageable);
}
