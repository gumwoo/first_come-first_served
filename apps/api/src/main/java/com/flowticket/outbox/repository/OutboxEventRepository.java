package com.flowticket.outbox.repository;

import com.flowticket.outbox.domain.OutboxEvent;
import com.flowticket.outbox.domain.OutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
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
     * purge 스윕 — 발행 완료 후 보존기간이 지난 행만 삭제.
     * PENDING·미발행 행은 대상에서 제외한다(유실 방지). status 가드가 있어 하네스 규칙도 만족.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutboxEvent o where o.status = :published and o.publishedAt < :threshold")
    int deletePublishedBefore(@Param("published") OutboxStatus published,
                              @Param("threshold") LocalDateTime threshold);
}
