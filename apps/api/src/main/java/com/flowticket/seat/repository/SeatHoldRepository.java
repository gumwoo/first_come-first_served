package com.flowticket.seat.repository;

import com.flowticket.seat.domain.SeatHold;
import com.flowticket.seat.domain.SeatHoldStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /** 1인 구매 한도 검사용 — 유저의 활성(HELD) 홀드 id. */
    @Query("select h.id from SeatHold h where h.userId = :userId and h.eventId = :eventId and h.status = :status")
    List<Long> findIdsByUser(@Param("userId") Long userId,
                             @Param("eventId") Long eventId,
                             @Param("status") SeatHoldStatus status);

    /** 만료 sweep 대상. */
    List<SeatHold> findByStatusAndExpiresAtBefore(SeatHoldStatus status, LocalDateTime now);
}
