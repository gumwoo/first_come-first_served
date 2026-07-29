package com.flowticket.foo.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** 위반 fixture: status를 바꾸면서 WHERE에 status 가드가 없는 무조건 UPDATE (check-then-act 레이스). */
public interface BadStatusRepository {

    @Modifying
    @Query("update SeatHold h set h.status = com.flowticket.seat.domain.SeatHoldStatus.EXPIRED "
            + "where h.id in :ids")
    int expireHoldsUnguarded(@Param("ids") List<Long> ids);
}
