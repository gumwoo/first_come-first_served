package com.flowticket.event.repository;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long>, EventRepositoryCustom {

    Optional<Event> findByKopisId(String kopisId);

    /**
     * 동기화 배치의 기존 여부를 <b>한 번에</b> 조회(IMP-012). 항목마다 findByKopisId를 부르면
     * N건에 SELECT N번이 나간다 — 수집분 전체를 한 쿼리로 읽어 메모리에서 신규/기존을 가른다.
     */
    List<Event> findAllByKopisIdIn(Collection<String> kopisIds);

    /** 운영 이벤트 목록(S07) — 최신순 페이징. */
    Page<Event> findAllByOrderByIdDesc(Pageable pageable);

    /** 좌석 시딩 대상: 판매 가능 상태의 이벤트 id (S04 자동 시딩). */
    @Query("select e.id from Event e where e.status in :statuses")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<EventStatus> statuses);
}
