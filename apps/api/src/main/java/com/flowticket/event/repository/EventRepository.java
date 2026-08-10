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

    /**
     * 상세를 아직 못 받은 공연 id. 동기화가 이 목록만 KOPIS 상세로 채운다.
     *
     * <p>매번 전량(1,446건)을 재호출하지 않기 위한 기준이다. KOPIS는 IP당 1초 10회 제한이 있어
     * 전량 재호출은 5분씩 걸리고 제한을 압박한다. 이미 받은 건 다시 부르지 않는다.
     *
     * <p>id만 뽑는 이유는 한 번에 다 로드하지 않기 위해서다 — 상세 수집은 건별 외부 호출이라
     * 오래 걸리므로, 엔티티를 통째로 들고 있으면 그 시간 내내 영속성 컨텍스트에 남는다.
     */
    @Query("select e.id from Event e where e.kopisId is not null and e.detailSyncedAt is null")
    List<Long> findIdsNeedingDetail(Pageable pageable);
}
