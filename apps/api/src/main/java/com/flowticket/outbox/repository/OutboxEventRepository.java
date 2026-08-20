package com.flowticket.outbox.repository;

import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** 릴레이 배치 — 미발행분을 오래된 순(적재 순서 = 발행 순서). ix_outbox_pending 부분 인덱스 사용. */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    /** 운영/테스트 — 미발행 적체 수(브로커 장애 관측). */
    long countByStatus(OutboxStatus status);

    /**
     * 선행 이벤트가 DEAD로 격리된 aggregate 목록. 릴레이가 매 틱 조회해 <b>같은 aggregate의 후속
     * 이벤트를 보류</b>하는 데 쓴다 — 앞선 이벤트가 나가지 못했는데 뒤 이벤트만 나가면 소비자가
     * 인과를 거꾸로 본다(예: PAID를 못 본 채 REFUNDED부터 수신).
     *
     * <p>키를 문자열로 합치는 이유: aggregateType과 aggregateId를 쌍으로 비교해야 하는데
     * JPQL 다중 컬럼 결과는 {@code Object[]}로 와서 호출부가 지저분해진다. DEAD는 드물어
     * 결과 집합이 작다.
     */
    @Query("select concat(o.aggregateType, ':', o.aggregateId) from OutboxEvent o where o.status = :dead")
    Set<String> findBlockedAggregateKeys(@Param("dead") OutboxStatus dead);

    /**
     * purge 스윕 — 발행 완료 후 보존기간이 지난 행만 삭제.
     * PENDING·미발행 행은 대상에서 제외한다(유실 방지). status 가드가 있어 하네스 규칙도 만족.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutboxEvent o where o.status = :published and o.publishedAt < :threshold")
    int deletePublishedBefore(@Param("published") OutboxStatus published,
                              @Param("threshold") LocalDateTime threshold);
}
