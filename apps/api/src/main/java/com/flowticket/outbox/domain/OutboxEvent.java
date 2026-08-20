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

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /**
     * id를 호출자가 정한다 — 같은 UUID를 payload 안(eventId)에도 넣어 <b>행 PK == 소비자 멱등 키</b>를
     * 맞추기 위함. 릴레이는 payload를 그대로 발행하므로 재발행돼도 소비자가 동일 키로 중복을 흡수한다.
     */
    public OutboxEvent(UUID id, String aggregateType, Long aggregateId, String type, String payload) {
        this.id = id;
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

    /**
     * <b>일시적</b> 발행 실패 — PENDING을 유지해 다음 틱에 재시도. 시도 횟수는 운영 가시성용.
     * 브로커·네트워크 장애가 여기 해당하며, 시도 횟수만으로 DEAD로 넘기지 않는다.
     */
    public void markAttemptFailed(String reason) {
        this.attempts++;
        this.lastError = truncate(reason);
    }

    /**
     * <b>결정적</b> 실패 — 재시도해도 결과가 같으므로 릴레이 후보에서 뺀다. payload를 해석할 수
     * 없어 애초에 Kafka로 보낼 객체를 만들지 못하는 경우다.
     *
     * <p>시도 횟수도 함께 올린다. "0회 시도인데 DEAD"로 보이면 운영자가 원인을 오해한다 —
     * 실제로는 한 번 시도했고 그 결과가 결정적이었다.
     */
    public void markDead(String reason) {
        this.attempts++;
        this.status = OutboxStatus.DEAD;
        this.lastError = truncate(reason);
    }

    /** 스택트레이스가 통째로 들어와 로그·조회를 뭉개지 않게 자른다. 판단에 필요한 건 앞부분이다. */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
