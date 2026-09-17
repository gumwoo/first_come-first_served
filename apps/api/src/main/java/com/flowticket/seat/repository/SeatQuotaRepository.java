package com.flowticket.seat.repository;

import com.flowticket.seat.domain.SeatHold;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 1인 구매 한도 계산 전용.
 *
 * <p>좌석 초과판매는 조건부 UPDATE가 막지만(ADR-003), 1인 한도는 단일 행에 표현되지 않는
 * 집계 규칙이라 같은 방식으로 지킬 수 없다. 원자화할 대상 행이 없기 때문이다.
 */
public interface SeatQuotaRepository extends Repository<SeatHold, Long> {

    /**
     * 이 사용자가 이 공연에서 실제로 붙들고 있는 서로 다른 좌석 수(HELD 홀드 + PAID 주문).
     *
     * <p>단일 SQL이어야 한다. 두 문장으로 나누면 READ COMMITTED에서 결제 커밋 사이에 0매로 읽힌다.
     * UNION으로 중복을 없앤다. 주문 후 같은 좌석이 order_items·seat_hold_items 양쪽에 있다(TS-013).
     */
    @Query(value = """
            select count(*) from (
                select oi.seat_id
                  from orders o
                  join order_items oi on oi.order_id = o.id
                 where o.user_id = :userId and o.event_id = :eventId and o.status = 'PAID'
                union
                select hi.seat_id
                  from seat_holds h
                  join seat_hold_items hi on hi.hold_id = h.id
                 where h.user_id = :userId and h.event_id = :eventId and h.status = 'HELD'
            ) active_seats
            """, nativeQuery = true)
    long countActiveSeats(@Param("userId") long userId, @Param("eventId") long eventId);

    /**
     * (사용자, 공연) 단위 직렬화: {@code pg_advisory_xact_lock}, 트랜잭션 종료 시 자동 해제.
     *
     * <p>고정 행이 없어 행 락으로는 새 홀드 삽입(팬텀)을 못 막는다. 세션 락이 아니라 xact 변형이어야
     * 풀에 반납된 커넥션이 락을 물려주지 않는다. 행 락·쿼터 행·Redis 락과의 비교는 TS-013.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:userKey, :eventKey)) acquired",
            nativeQuery = true)
    Integer acquireQuotaLock(@Param("userKey") int userKey, @Param("eventKey") int eventKey);
}
