package com.flowticket.seat.repository;

import com.flowticket.seat.domain.EventSeatPrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSeatPriceRepository extends JpaRepository<EventSeatPrice, Long> {

    List<EventSeatPrice> findByEventId(Long eventId);

    boolean existsByEventId(Long eventId);
}
