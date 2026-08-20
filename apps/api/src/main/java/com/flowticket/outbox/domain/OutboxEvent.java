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

    /**
     * <b>마지막 시도의 실패 원인.</b> 성공하면 비운다 — 남겨두면 PUBLISHED인데 오류가 붙어 있는
     * 모순된 상태가 되어 운영자가 "발행됐는데 아직 문제가 있나"로 읽는다.
     */
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
        // 앞선 틱의 일시적 실패 기록을 지운다. attempts는 남겨 "몇 번 만에 나갔는지"를 보존한다.
        this.lastError = null;
    }

    /**
     * <b>일시적</b> 발행 실패 — PENDING을 유지해 다음 틱에 재시도. 시도 횟수는 운영 가시성용.
     * 브로커·네트워크 장애가 여기 해당하며, 시도 횟수만으로 DEAD로 넘기지 않는다.
     *
     * <p>여기서 남긴 원인은 <b>다음 시도가 성공하면 지워진다</b>({@link #markPublished()}).
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

    /**
     * 운영자 판단 — 다시 발행 대상으로 되돌린다(DEAD → PENDING).
     *
     * <p>payload를 고치는 기능은 <b>일부러 제공하지 않는다</b>. 운영자가 이벤트 내용을 편집할 수
     * 있으면 그것은 복구가 아니라 위조다. 이 경로가 유효한 실제 상황은 <b>소비할 쪽이 배포로
     * 고쳐진 경우</b>다 — 스키마가 맞춰졌으면 같은 payload가 이제 해석된다.
     *
     * <p>되돌린 뒤에도 정렬 키(createdAt)는 그대로라 원래 순서 자리로 돌아간다.
     */
    public void requeue() {
        requireDead("재발행");
        this.status = OutboxStatus.PENDING;
        this.lastError = null;
    }

    /**
     * 운영자 판단 — 이 이벤트의 발행을 포기한다(DEAD → DISCARDED).
     *
     * <p><b>같은 aggregate의 후속 이벤트가 다시 흐른다.</b> 릴레이는 DEAD만 차단 사유로 보므로,
     * 폐기는 "이 이벤트는 영영 안 나간다는 것을 받아들인다"는 선언이기도 하다. 행은 지우지 않는다 —
     * 무엇을 포기했는지가 남아야 나중에 추적할 수 있다.
     */
    public void discard() {
        requireDead("폐기");
        this.status = OutboxStatus.DISCARDED;
    }

    /**
     * PENDING 행에는 두 전이를 허용하지 않는다. 브로커 장애로 밀려 있을 뿐 언젠가 나갈 이벤트라,
     * 운영자가 개입할 대상이 아니다 — 개입해야 하는 것은 <b>릴레이가 스스로 못 푸는</b> DEAD뿐이다.
     */
    private void requireDead(String action) {
        if (this.status != OutboxStatus.DEAD) {
            throw new IllegalStateException(
                    action + " 대상이 아니다: id=" + id + " status=" + status + " (DEAD만 허용)");
        }
    }

    /** 스택트레이스가 통째로 들어와 로그·조회를 뭉개지 않게 자른다. 판단에 필요한 건 앞부분이다. */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
