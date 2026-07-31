-- S08 1단계: 트랜잭셔널 아웃박스(ADR-010).
-- 비즈니스 트랜잭션과 같은 tx에서 이벤트를 여기 적재 → 폴링 릴레이가 Kafka로 발행(publish-then-mark).
-- AFTER_COMMIT 직접 발행의 "커밋 직후·발행 전 크래시" 유실 구멍을 닫는다.
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,                    -- 이벤트 식별자 = 소비자 멱등 키
    aggregate_type VARCHAR(40)  NOT NULL,                       -- 'order'
    aggregate_id   BIGINT       NOT NULL,                       -- orderId = Kafka 파티션 키(주문별 순서)
    type           VARCHAR(60)  NOT NULL,                       -- 'order.paid'
    payload        TEXT         NOT NULL,                       -- 직렬화된 이벤트(JSON)
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',     -- OutboxStatus: PENDING|PUBLISHED
    attempts       INT          NOT NULL DEFAULT 0,             -- 발행 시도 횟수(운영 가시성)
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    published_at   TIMESTAMP                                    -- 발행 성공 시각(purge 기준)
);

-- 릴레이 조회: PENDING만 오래된 순. 부분 인덱스라 PUBLISHED가 쌓여도 스캔 비용이 낮다.
CREATE INDEX ix_outbox_pending ON outbox_events (created_at) WHERE status = 'PENDING';
-- purge 스윕: PUBLISHED 중 보존기간(7일) 지난 행 삭제용.
CREATE INDEX ix_outbox_published_at ON outbox_events (published_at) WHERE status = 'PUBLISHED';

COMMENT ON TABLE outbox_events IS '트랜잭셔널 아웃박스(S08, ADR-010). 같은 tx 적재 → 릴레이 발행 → PUBLISHED 7일 보존 후 purge.';
