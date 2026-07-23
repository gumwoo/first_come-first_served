package com.flowticket.dlq.dto;

import com.flowticket.dlq.domain.DlqMessage;
import java.time.LocalDateTime;

/** 운영 DLQ 목록/상세 항목(S07 Phase 4c). */
public record DlqMessageSummary(
        Long id,
        String topic,
        String payload,
        String errorMessage,
        String status,
        LocalDateTime createdAt,
        LocalDateTime retriedAt) {

    public static DlqMessageSummary from(DlqMessage m) {
        return new DlqMessageSummary(
                m.getId(), m.getTopic(), m.getPayload(), m.getErrorMessage(),
                m.getStatus().name(), m.getCreatedAt(), m.getRetriedAt());
    }
}
