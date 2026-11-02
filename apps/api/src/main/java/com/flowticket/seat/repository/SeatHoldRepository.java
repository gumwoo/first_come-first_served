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
     * 만료 sweep: 홀드 일괄 EXPIRED(조건부). 여전히 HELD인 홀드만 만료한다.
     * 결제가 경합해 CONVERTED로 바뀐 홀드를 EXPIRED로 덮어쓰지 않게 하는 가드(TS-011).
     */
    @Modifying(clearAutomatically = true)
    @Query("update SeatHold h set h.status = com.flowticket.seat.domain.SeatHoldStatus.EXPIRED "
            + "where h.id in :ids and h.status = com.flowticket.seat.domain.SeatHoldStatus.HELD")
    int expireHolds(@Param("ids") List<Long> ids);

    /** 결제 확정: HELD 홀드만 CONVERTED로(조건부, 만료 sweep 대상에서 제외). */
    @Modifying(clearAutomatically = true)
    @Query("update SeatHold h set h.status = com.flowticket.seat.domain.SeatHoldStatus.CONVERTED "
            + "where h.id = :id and h.status = com.flowticket.seat.domain.SeatHoldStatus.HELD")
    int convertHold(@Param("id") Long id);

    /**
     * 수동 해제: HELD 홀드만 RELEASED로(조건부). 이 전이에 이긴 쪽만 좌석을 풀 수 있다.
     * 만료 sweep에 지고도 좌석을 풀면 그사이 다른 사용자가 잡은 새 선점이 풀린다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update SeatHold h set h.status = com.flowticket.seat.domain.SeatHoldStatus.RELEASED "
            + "where h.id = :id and h.status = com.flowticket.seat.domain.SeatHoldStatus.HELD")
    int releaseHold(@Param("id") Long id);

    /** 만료 sweep 대상. */
    List<SeatHold> findByStatusAndExpiresAtBefore(SeatHoldStatus status, LocalDateTime now);
}
