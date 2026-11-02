package com.flowticket.seat.repository;

import com.flowticket.seat.domain.EventSeatPrice;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventSeatPriceRepository extends JpaRepository<EventSeatPrice, Long> {

    List<EventSeatPrice> findByEventId(Long eventId);

    boolean existsByEventId(Long eventId);

    /**
     * 시딩 전 남은 가격 제거. 벌크라 바로 실행된다. 파생 삭제는 flush까지 미뤄지고 IDENTITY INSERT는 바로 나가,
     * 이어서 넣는 같은 등급 가격이 unique에 걸린다.
     */
    @Modifying
    @Query("delete from EventSeatPrice p where p.eventId = :eventId")
    int deleteByEventId(@Param("eventId") Long eventId);
}
