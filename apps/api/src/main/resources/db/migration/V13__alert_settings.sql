-- S07 alerts: 운영 알림 임계치(단일 행 설정).
-- 현재는 DLQ 적체(PENDING) 임계치 하나. 초과 시 대시보드/응답에서 breached=true.
CREATE TABLE alert_settings (
    id                    BIGINT      PRIMARY KEY,
    dlq_pending_threshold INTEGER     NOT NULL DEFAULT 1,
    updated_at            TIMESTAMP   NOT NULL DEFAULT now()
);

-- 단일 진실원 행(id=1). 멱등.
INSERT INTO alert_settings (id, dlq_pending_threshold) VALUES (1, 1)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE alert_settings IS '운영 알림 임계치(S07). 단일 행(id=1). 현재 DLQ 적체 임계치.';
