package com.flowticket.order.repository;

import com.flowticket.order.domain.RefundAttempt;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, Long> {

    /**
     * 정산 후보: 아직 확인되지 않은 환불 시도.
     *
     * 결제 시각이 아니라 시도 시각으로 자른다. 환불 가능 여부는 공연일까지 남은 날로 정해지므로
     * 한 달 전에 결제한 주문이 오늘 환불될 수 있고, 결제 시각을 기준으로 삼으면 그 건을 영영
     * 못 본다.
     *
     * 정렬은 마지막 조회 시각(NULLS FIRST = 한 번도 안 본 것 먼저)이다. 조회해도 확인되지 않는
     * 시도(PG 조회 실패)가 있으므로, 표시 없이 돌면 앞쪽만 반복하고 뒤쪽이 굶는다.
     */
    @Query("select a from RefundAttempt a where a.resolved = false "
            + "and a.createdAt < :before and a.createdAt > :after "
            + "and (a.checkedAt is null or a.checkedAt < :recheckBefore) "
            + "order by a.checkedAt asc nulls first, a.createdAt asc")
    List<RefundAttempt> findReconcileCandidates(@Param("before") LocalDateTime before,
                                                @Param("after") LocalDateTime after,
                                                @Param("recheckBefore") LocalDateTime recheckBefore,
                                                Pageable pageable);

    /**
     * 시도 기록. 같은 멱등키가 이미 있으면 아무것도 하지 않는다(재시도·더블클릭).
     *
     * 애플리케이션에서 DataIntegrityViolationException을 잡아 무시하면 멱등키 충돌뿐 아니라
     * 길이 초과·FK 위반 같은 예상 못 한 제약 위반까지 함께 삼킨다. 그 경우 시도 기록 없이
     * PG 취소가 나가고, 뒤이어 refunds INSERT가 같은 이유로 실패해 정산 안전망까지 비어 버린다.
     * 무시할 충돌을 DB에 명시해 그 구멍을 막는다.
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into refund_attempts (order_id, idempotency_key, resolved, created_at)
            values (:orderId, :key, false, now())
            on conflict (idempotency_key) do nothing
            """, nativeQuery = true)
    int record(@Param("orderId") Long orderId, @Param("key") String key);

    Optional<RefundAttempt> findByIdempotencyKey(String idempotencyKey);

    /**
     * PG와 DB가 어긋나지 않음이 확인된 시도를 후보에서 뺀다.
     *
     * 멱등키만으로 지목하지 않는다. 키는 클라이언트가 만들고 UNIQUE는 전역이라, 다른 주문이 같은
     * 키를 재사용하면 그 주문의 성공이 남의 미해결 시도를 닫아 버린다. 주문까지 같아야 닫는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RefundAttempt a set a.resolved = true "
            + "where a.orderId = :orderId and a.idempotencyKey = :key")
    int resolve(@Param("orderId") Long orderId, @Param("key") String key);

    /**
     * 조회한 후보에 표시를 남긴다. 확인 여부와 무관하게 남겨야 순회가 앞으로 나간다.
     * 정산 잡은 트랜잭션 없이 돌므로 이 쓰기만 자체 트랜잭션으로 연다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update RefundAttempt a set a.checkedAt = :now where a.id in :ids")
    int markChecked(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
