package com.flowticket.seat.repository;

import com.flowticket.seat.domain.SeatHold;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 1인 구매 한도 계산 전용.
 *
 * <p>좌석 초과판매는 조건부 UPDATE가 막지만(ADR-003), <b>1인 한도는 단일 행에 표현되지 않는
 * 집계 규칙</b>이라 같은 방식으로 지킬 수 없다. 원자화할 대상 행이 없기 때문이다.
 */
public interface SeatQuotaRepository extends Repository<SeatHold, Long> {

    /**
     * 이 사용자가 이 공연에서 <b>실제로 붙들고 있는 서로 다른 좌석 수</b>.
     *
     * <p>점유의 진실원은 두 곳뿐이다:
     * <pre>
     *   결제 전 점유 → seat_holds.status = 'HELD'
     *   결제 후 점유 → orders.status     = 'PAID'
     * </pre>
     *
     * <p><b>주문의 중간 상태(PENDING·VBANK_WAITING)는 세지 않는다.</b> 좌석을 점유 중이라면
     * 이미 HELD 홀드로 잡히고, 홀드가 풀린 뒤(수동 해제·만료) 남은 주문은 좌석을 확보하고 있지
     * 않다. 특히 홀드가 EXPIRED면 결제 확정 시 convertHold가 0행이라 그 주문은 PAID가 될 수
     * 없다 — 그런 주문까지 세면 좌석을 이미 반납한 사용자를 주문 만료까지 묶어두게 된다.
     *
     * <p><b>반드시 단일 SQL이어야 한다.</b> "PAID 좌석 수"와 "HELD 좌석 수"를 두 문장으로
     * 나누어 읽으면 READ COMMITTED에서 문장마다 스냅샷이 달라, 그 사이에 결제가 커밋되면
     * 앞 문장은 결제 전(PAID 0)을, 뒤 문장은 결제 후(HELD 0)를 보아 <b>0매로 계산</b>된다.
     * 한 문장이면 결제 전(0+4) 또는 결제 후(4+0) 중 하나만 보므로 항상 4다.
     *
     * <p><b>UNION은 중복을 제거한다.</b> 주문 생성 후에는 같은 좌석이 order_items와
     * seat_hold_items 양쪽에 존재하므로, 단순 합산하면 4매가 8매가 되어 정상 사용자를 막는다.
     * (그래서 UNION ALL이 아니며, 바깥 DISTINCT도 필요 없다.)
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
     * (사용자, 공연) 단위 비관적 직렬화 — 트랜잭션 종료 시 자동 해제.
     *
     * <p>왜 행 락이 아닌가: <b>현재 데이터 모델에 (사용자, 공연) 한도를 대표하는 고정 행이
     * 없다.</b> 기존 홀드 행을 잠가도 <b>새 홀드의 삽입</b>은 막지 못한다(팬텀) —
     * REPEATABLE READ로 올려도 마찬가지이고 SERIALIZABLE은 재시도 비용이 크다.
     *
     * <p>"행 락이 원리적으로 불가능하다"는 뜻은 아니다. 쿼터 행(user_event_quota)을 두면
     * 거기에 {@code FOR UPDATE}를 걸거나 조건부 UPDATE로 원자화할 수 있다. 그 안을 택하지
     * 않은 이유는 <b>비정규화 카운터를 해제·만료·환불 경로마다 정확히 줄여야 하는데,
     * 이 프로젝트에서 반복해서 깨진 곳이 바로 그 경로들이기 때문이다</b>(TS-007·010·011).
     * 파생 상태를 늘리지 않고 원본에서 세는 쪽을 골랐다.
     *
     * <p>왜 Redis 분산락이 아닌가: 리스 만료와 트랜잭션 수명이 따로 놀아 만료 후 소유자가 둘이
     * 될 수 있다. advisory lock은 <b>데이터와 같은 시스템</b>에서 잡히고 커밋·롤백과 함께 풀린다.
     *
     * <p>왜 {@code xact} 변형인가: 세션 범위 락은 커넥션이 락을 쥔 채 풀에 반납되어 다음 요청이
     * 물려받는다. 트랜잭션 범위는 그 문제가 원천적으로 없다.
     *
     * <p>좌석 총량을 늘리는 진입점이 {@code SeatService.hold()} 하나이므로 이 경로만 직렬화하면
     * 충분하다. 결제는 표현 위치만 옮기고(HELD→PAID), 환불·해제·만료는 수를 줄인다.
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:userKey, :eventKey)) acquired",
            nativeQuery = true)
    Integer acquireQuotaLock(@Param("userKey") int userKey, @Param("eventKey") int eventKey);
}
