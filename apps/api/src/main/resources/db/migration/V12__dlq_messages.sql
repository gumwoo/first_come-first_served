-- S07 Phase 4c: Dead Letter Queue 적재 테이블.
-- consumer 재시도 소진 후 DLT로 넘어온 메시지를 운영자가 조회·재시도·폐기할 수 있게 보존.
CREATE TABLE dlq_messages (
    id             BIGSERIAL   PRIMARY KEY,
    topic          VARCHAR(200) NOT NULL,          -- 원본 토픽(재발행 대상)
    payload        TEXT        NOT NULL,            -- 실패 메시지 페이로드(JSON)
    error_message  TEXT,                            -- 소비 실패 원인
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- DlqStatus
    created_at     TIMESTAMP   NOT NULL DEFAULT now(),
    retried_at     TIMESTAMP                        -- 재시도/폐기 처리 시각
);

CREATE INDEX ix_dlq_status_id ON dlq_messages (status, id DESC);
COMMENT ON TABLE dlq_messages IS 'DLQ 적재(S07 Phase4c). consumer 재시도 소진 → DLT → 여기 적재.';
