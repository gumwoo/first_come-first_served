package com.flowticket.seat.repository;

import com.flowticket.seat.domain.SeatHold;
import com.flowticket.seat.domain.SeatHoldStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * 만료 sweep: 홀드 일괄 EXPIRED — <b>조건부</b>. 여전히 HELD인 홀드만 만료한다.
     * 결제가 경합해 CONVERTED로 바뀐 홀드를 EXPIRED로 덮어쓰지 않게 하는 가드(TS-011).
     */
    @Modifying(clearAutomatically = true)
    @Query("update SeatHold h set h.status = com.flowticket.seat.domain.SeatHoldStatus.EXPIRED "
            + "where h.id in :ids and h.status = com.flowticket.seat.domain.SeatHoldStatus.HELD")
    int expireHolds(@Param("ids") List<Long> ids);

    /** 결제 확정 — HELD 홀드만 CONVERTED로(조건부, 만료 sweep 대상에서 제외). */
    @Modifying(clearAutomatically = true)
    @Query("update SeatHold h set h.status = com.flowticket.seat.domain.SeatHoldStatus.CONVERTED "
            + "where h.id = :id and h.status = com.flowticket.seat.domain.SeatHoldStatus.HELD")
    int convertHold(@Param("id") Long id);


    /** 만료 sweep 대상. */
    List<SeatHold> findByStatusAndExpiresAtBefore(SeatHoldStatus status, LocalDateTime now);
}
