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
     * 상세를 받아야 할 공연 id. <b>미수집 + 오래된 것</b> 둘 다 대상이다.
     *
     * <pre>
     *   detailSyncedAt IS NULL           → 아직 한 번도 못 받음 (최우선)
     *   detailSyncedAt &lt; staleBefore     → 받은 지 오래됨 (순환 갱신)
     *   정렬: 오래된 순, NULL 먼저
     * </pre>
     *
     * <p><b>왜 오래된 것도 넣나.</b> 초안은 NULL만 대상으로 삼아 <b>한 번 채우면 영원히 다시
     * 보지 않았다.</b> KOPIS에서 가격·출연진·공연시간이 바뀌어도 우리 값은 그대로 남는다.
     *
     * <p><b>왜 전량을 매일 다시 받지 않나.</b> 공연 약 1,446건인데 레이트 리밋(5회/초) 때문에
     * 전량이면 약 5분이고, 회차당 상한이 300건이라 매일 전부를 대상으로 만들면 계속 밀린다.
     * 대신 <b>오래된 순으로 300건씩 순환</b>시킨다 — 전체가 한 바퀴 도는 데 약 5일이 걸리고,
     * 그 사이 KOPIS 호출량은 하루 300건으로 일정하다.
     *
     * <p><b>알려진 한계</b>: 정렬이 NULL을 앞에 두므로, 상세 조회가 <b>영구적으로 실패하는</b>
     * 공연이 회차 상한(300)보다 많으면 그것들이 매 회차를 차지해 뒤가 굶는다. 실패 시
     * {@code detailSyncedAt}을 남기지 않는 설계의 대가다. 실제로 그런 상황이 생기면
     * "시도 시각"을 성공 시각과 분리해 기록해야 한다 — 지금은 넣지 않았다.
     *
     * <p>id만 뽑는 이유는 한 번에 다 로드하지 않기 위해서다 — 상세 수집은 건별 외부 호출이라
     * 오래 걸리므로, 엔티티를 통째로 들고 있으면 그 시간 내내 영속성 컨텍스트에 남는다.
     */
    @Query("""
            select e.id from Event e
             where e.kopisId is not null
               and (e.detailSyncedAt is null or e.detailSyncedAt < :staleBefore)
             order by e.detailSyncedAt asc nulls first
            """)
    /**
     * 상세를 <b>한 번도 못 받은</b> 공연 수.
     *
     * <p>{@code findIdsNeedingDetail}과 조건이 일부러 다르다 — 그쪽은 "오래된 것"도 대상에
     * 넣어 순환 갱신하므로 <b>0이 될 수 없다</b>(7일이 지나면 다시 대상이 된다).
     * 이 값은 "초기 수집이 끝났는가"를 묻는 것이라 NULL만 센다.
     *
     * <p>⚠️ {@code runningTime} 같은 개별 필드로 대신 판정하면 안 된다.
     * {@code Event.updateDetail()}은 상세 응답에 그 필드가 없어도 {@code detailSyncedAt}을
     * 찍는다 — 즉 <b>상세는 받았는데 runningTime만 비어 있는 공연</b>이 정상적으로 존재한다.
     */
    long countByKopisIdIsNotNullAndDetailSyncedAtIsNull();

    /** KOPIS에서 온 공연 수(분모). */
    long countByKopisIdIsNotNull();

    List<Long> findIdsNeedingDetail(@Param("staleBefore") LocalDateTime staleBefore,
                                    Pageable pageable);
}
