package com.flowticket.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 운영 알림 임계치. 단일 행(id=1). 마이그레이션 V13에서 시드되고, V20에서 아웃박스 격리 임계치가 더해졌다. */
@Entity
@Table(name = "alert_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertSettings {

    /** 단일 설정 행 식별자. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "dlq_pending_threshold", nullable = false)
    private int dlqPendingThreshold;

    /** 아웃박스 격리(DEAD) 행 경고 임계치(V20). Kafka 제거 뒤 DLQ 임계치를 대신한다(ADR-028). */
    @Column(name = "outbox_dead_threshold", nullable = false)
    private int outboxDeadThreshold;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 임계치 갱신. null인 값은 그대로 둔다(한쪽만 보내는 구·신 화면이 롤링 배포 중 공존한다). */
    public void updateThresholds(Integer dlqPendingThreshold, Integer outboxDeadThreshold) {
        if (dlqPendingThreshold != null) {
            this.dlqPendingThreshold = dlqPendingThreshold;
        }
        if (outboxDeadThreshold != null) {
            this.outboxDeadThreshold = outboxDeadThreshold;
        }
        this.updatedAt = LocalDateTime.now();
    }
}
