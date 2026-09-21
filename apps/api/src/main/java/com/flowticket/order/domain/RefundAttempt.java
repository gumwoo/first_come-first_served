package com.flowticket.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 환불 시도 기록(ADR-011). 환불 정산이 확인할 작업 목록이다.
 *
 * 성공한 환불의 장부는 refunds다. 이쪽은 PG 취소 이후 DB 쓰기가 실패해 롤백된 시도까지 남긴다.
 * 그래서 환불 트랜잭션 밖에서, 그 트랜잭션이 열리기 전에 기록한다.
 *
 * resolved는 "PG와 DB가 어긋나지 않았음이 확인됐다"는 뜻이다. 환불이 정상 완료됐거나,
 * 정산이 PG에 물어 취소가 없음을 확인한 경우 둘 다 해당한다.
 */
@Entity
@Table(name = "refund_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Column(nullable = false)
    private boolean resolved;

    /** 정산이 마지막으로 PG에 조회한 시각. 후보를 오래 안 본 순서로 돌리는 데 쓴다. */
    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private RefundAttempt(Long orderId, String idempotencyKey) {
        this.orderId = orderId;
        this.idempotencyKey = idempotencyKey;
        this.resolved = false;
        this.createdAt = LocalDateTime.now();
    }
}
