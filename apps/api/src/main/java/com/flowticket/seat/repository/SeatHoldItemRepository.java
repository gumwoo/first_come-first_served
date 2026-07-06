package com.flowticket.seat.repository;

import com.flowticket.seat.domain.SeatHoldItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatHoldItemRepository extends JpaRepository<SeatHoldItem, Long> {

    List<SeatHoldItem> findByHoldId(Long holdId);

    /** 1인 구매 한도 검사 — 활성 홀드들의 좌석 수. */
    long countByHoldIdIn(List<Long> holdIds);
}
