package com.flowticket.alert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 운영 알림 임계치(S07). 단일 행(id=1). 마이그레이션 V13에서 시드된다. */
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

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 임계치 갱신. */
    public void updateThreshold(int dlqPendingThreshold) {
        this.dlqPendingThreshold = dlqPendingThreshold;
        this.updatedAt = LocalDateTime.now();
    }
}
