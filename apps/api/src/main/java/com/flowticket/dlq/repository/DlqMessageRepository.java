package com.flowticket.dlq.repository;

import com.flowticket.dlq.domain.DlqMessage;
import com.flowticket.dlq.domain.DlqStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DlqMessageRepository extends JpaRepository<DlqMessage, Long> {

    /** 운영 DLQ 목록 — 최신순 페이징. */
    Page<DlqMessage> findAllByOrderByIdDesc(Pageable pageable);

    /** 운영 DLQ 목록 — 상태 필터, 최신순. */
    Page<DlqMessage> findByStatusOrderByIdDesc(DlqStatus status, Pageable pageable);

    /** 대시보드/알림 — 미처리(PENDING) 적체 수. */
    long countByStatus(DlqStatus status);
}
