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

    // dlq_pending_threshold 컬럼은 매핑하지 않는다(Kafka 제거 — ADR-028). 컬럼은 N+1 릴리스에서 지운다(Expand-Contract):
    // 롤링 중 구버전 Pod가 아직 매핑하고 있어 이 릴리스에서 지우면 기동이 깨진다(kafka-removal-design §5.3).

    /** 아웃박스 격리(DEAD) 행 경고 임계치(V20). */
    @Column(name = "outbox_dead_threshold", nullable = false)
    private int outboxDeadThreshold;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 임계치 갱신. */
    public void updateThreshold(int outboxDeadThreshold) {
        this.outboxDeadThreshold = outboxDeadThreshold;
        this.updatedAt = LocalDateTime.now();
    }
}
