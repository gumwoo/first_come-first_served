package com.flowticket.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트랜잭셔널 아웃박스 행(S08, ADR-010). 비즈니스 트랜잭션과 <b>같은 tx</b>에서 적재되어,
 * 결제가 롤백되면 이 행도 함께 사라진다(유령 이벤트 0). 폴링 릴레이가 PENDING을 Kafka로 발행하고
 * 성공 후 PUBLISHED로 마킹한다(publish-then-mark → at-least-once, 유실 0).
 *
 * <p>id(UUID)는 소비자 멱등 키로도 쓰인다(`dedup:order-event:{id}`).
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(nullable = false, length = 60)
    private String type;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public OutboxEvent(String aggregateType, Long aggregateId, String type, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = LocalDateTime.now();
    }

    /** 발행 성공 — 반드시 Kafka send 성공을 확인한 뒤 호출(publish-then-mark). */
    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /** 발행 실패 — PENDING을 유지해 다음 틱에 재시도. 시도 횟수는 운영 가시성용. */
    public void markAttemptFailed() {
        this.attempts++;
    }
}
