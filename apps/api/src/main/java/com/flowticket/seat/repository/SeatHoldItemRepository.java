package com.flowticket.seat.repository;

import com.flowticket.seat.domain.SeatHoldItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatHoldItemRepository extends JpaRepository<SeatHoldItem, Long> {

    List<SeatHoldItem> findByHoldId(Long holdId);
}
