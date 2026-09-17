package com.flowticket.event.repository;

import com.flowticket.event.domain.Event;
import com.flowticket.event.domain.EventStatus;
import java.time.LocalDateTime;
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
     * 동기화 배치의 기존 여부를 한 번에 조회(IMP-012). 항목마다 findByKopisId를 부르면
     * N건에 SELECT N번이 나간다 — 수집분 전체를 한 쿼리로 읽어 메모리에서 신규/기존을 가른다.
     */
    List<Event> findAllByKopisIdIn(Collection<String> kopisIds);

    /** 운영 이벤트 목록 — 최신순 페이징. */
    Page<Event> findAllByOrderByIdDesc(Pageable pageable);

    /** 좌석 시딩 대상: 판매 가능 상태의 이벤트 id (자동 시딩). */
    @Query("select e.id from Event e where e.status in :statuses")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<EventStatus> statuses);

    /**
     * 상세를 받아야 할 공연 id — 미수집(NULL)과 오래된 것을 오래된 순(NULL 먼저)으로 고른다.
     * 회차당 상한으로 순환시키는 이유와 알려진 한계(영구 실패 공연이 많으면 뒤가 굶음)는 TS-033.
     * 엔티티가 아니라 id만 뽑아, 건별 외부 호출 동안 영속성 컨텍스트에 남기지 않는다.
     */
    @Query("""
            select e.id from Event e
             where e.kopisId is not null
               and (e.detailSyncedAt is null or e.detailSyncedAt < :staleBefore)
             order by e.detailSyncedAt asc nulls first
            """)
    List<Long> findIdsNeedingDetail(@Param("staleBefore") LocalDateTime staleBefore,
                                    Pageable pageable);

    /**
     * 상세를 한 번도 못 받은 공연 수("초기 수집이 끝났는가"). 순환 갱신 대상과 달리 NULL만 센다.
     * {@code runningTime} 같은 개별 필드로 대신 세면 틀린다(TS-033).
     */
    long countByKopisIdIsNotNullAndDetailSyncedAtIsNull();

    /** KOPIS에서 온 공연 수(분모). */
    long countByKopisIdIsNotNull();
}
