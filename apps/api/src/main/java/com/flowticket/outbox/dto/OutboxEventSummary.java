package com.flowticket.outbox.dto;

import com.flowticket.outbox.domain.OutboxEvent;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 운영 아웃박스 목록/상세 항목(S08).
 *
 * <p>payload를 그대로 노출한다 — 격리된 행을 판단하려면 <b>무엇이 깨졌는지</b>를 봐야 하고,
 * 그게 이 화면의 존재 이유다(운영 DLQ 조회도 같은 이유로 payload를 싣는다).
 */
public record OutboxEventSummary(
        UUID id,
        String aggregateType,
        Long aggregateId,
        String type,
        String payload,
        String status,
        int attempts,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime publishedAt) {

    public static OutboxEventSummary from(OutboxEvent e) {
        return new OutboxEventSummary(
                e.getId(), e.getAggregateType(), e.getAggregateId(), e.getType(), e.getPayload(),
                e.getStatus().name(), e.getAttempts(), e.getLastError(),
                e.getCreatedAt(), e.getPublishedAt());
    }
}
