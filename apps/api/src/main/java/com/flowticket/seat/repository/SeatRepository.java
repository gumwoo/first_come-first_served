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

    /** 주어진 이벤트들 중 이미 좌석이 있는 id(자동 시딩에서 건너뛸 대상). */
    @Query("select distinct s.eventId from Seat s where s.eventId in :ids")
    List<Long> findSeededEventIds(@Param("ids") List<Long> ids);

    /**
     * 좌석 선점 — 조건부 UPDATE로 원자화. AVAILABLE인 좌석만 HELD로 바꾸고 바뀐 행 수를 반환.
     * 반환 수 != 요청 수 이면 일부가 이미 선점됨(SOLD_OUT). 초과판매 원천 차단(ADR-003).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = :held, s.updatedAt = CURRENT_TIMESTAMP "
            + "where s.id in :ids and s.status = :available")
    int holdIfAvailable(@Param("ids") List<Long> ids,
                        @Param("held") SeatStatus held,
                        @Param("available") SeatStatus available);

    /** 좌석 상태 복구(해제/만료 시). */
    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = :available, s.updatedAt = CURRENT_TIMESTAMP where s.id in :ids")
    int releaseSeats(@Param("ids") List<Long> ids, @Param("available") SeatStatus available);
}
