package com.flowticket.dlq.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DLQ 적재 메시지(S07 Phase 4c). DLT에서 넘어온 실패 메시지를 운영 조회/재처리용으로 보존. */
@Entity
@Table(name = "dlq_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DlqMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DlqStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "retried_at")
    private LocalDateTime retriedAt;

    public DlqMessage(String topic, String payload, String errorMessage) {
        this.topic = topic;
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.status = DlqStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    /** 원본 토픽으로 재발행 완료 표시. */
    public void markRetried() {
        this.status = DlqStatus.RETRIED;
        this.retriedAt = LocalDateTime.now();
    }

    /** 운영자 폐기. */
    public void markDiscarded() {
        this.status = DlqStatus.DISCARDED;
        this.retriedAt = LocalDateTime.now();
    }
}
