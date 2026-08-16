package com.flowticket.seat.repository;

import com.flowticket.seat.domain.Seat;
import com.flowticket.seat.domain.SeatStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    boolean existsByEventId(Long eventId);

    List<Seat> findByEventId(Long eventId);

    /** 주어진 좌석들 중 해당 이벤트 소속인 개수(요청 좌석의 event 소속 검증용). */
    long countByIdInAndEventId(List<Long> ids, Long eventId);

    /**
     * 공연의 잔여 좌석 수 — 선점 실패가 <b>매진</b>인지 <b>좌석 경합</b>인지 가르는 데만 쓴다.
     * 실패 경로에서만 도는 질의라 정상 선점에는 비용이 붙지 않는다.
     */
    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    /** 주어진 이벤트들 중 이미 좌석이 있는 id(자동 시딩에서 건너뛸 대상). */
    @Query("select distinct s.eventId from Seat s where s.eventId in :ids")
    List<Long> findSeededEventIds(@Param("ids") List<Long> ids);

    /**
     * 좌석 선점 — 조건부 UPDATE로 원자화. AVAILABLE인 좌석만 HELD로 바꾸고 바뀐 행 수를 반환.
     * 반환 수 != 요청 수 이면 일부가 이미 선점된 것이다 — 전량 롤백한다(초과판매 원천 차단, ADR-003).
     * 그 실패를 SOLD_OUT으로 볼지 SEAT_CONFLICT로 볼지는 <b>잔여 좌석 수</b>가 가른다(SeatService 참고).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = :held, s.updatedAt = CURRENT_TIMESTAMP "
            + "where s.id in :ids and s.eventId = :eventId and s.status = :available")
    int holdIfAvailable(@Param("ids") List<Long> ids,
                        @Param("eventId") Long eventId,
                        @Param("held") SeatStatus held,
                        @Param("available") SeatStatus available);

    /**
     * 좌석 상태 복구(해제/만료/환불) — <b>조건부</b>. 기대 상태(:from)인 좌석만 복구한다.
     * 만료·수동해제는 from=HELD, 환불은 from=SOLD. 반환 수 != 요청 수면 이미 다른 경로가 상태를 바꿈.
     * 가드가 없으면 결제 확정(HELD→SOLD)과 만료 sweep이 경합할 때 SOLD 좌석을 AVAILABLE로
     * 덮어써 재판매(초과판매)가 날 수 있다 — 이 가드가 그 레이스를 차단한다(TS-011).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = :available, s.updatedAt = CURRENT_TIMESTAMP "
            + "where s.id in :ids and s.status = :from")
    int releaseSeats(@Param("ids") List<Long> ids,
                     @Param("available") SeatStatus available,
                     @Param("from") SeatStatus from);

    /** 결제 확정 — HELD 좌석만 SOLD로(조건부). 반환 수 != 요청 수면 일부가 이미 풀림. */
    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = :sold, s.updatedAt = CURRENT_TIMESTAMP "
            + "where s.id in :ids and s.status = :held")
    int sellSeats(@Param("ids") List<Long> ids,
                  @Param("sold") SeatStatus sold,
                  @Param("held") SeatStatus held);
}
