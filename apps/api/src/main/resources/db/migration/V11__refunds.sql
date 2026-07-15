-- 환불 기록(S06). 취소·환불 1건당 1행. 감사추적 + 멱등(idempotency_key UNIQUE)으로 이중 환불 차단.
CREATE TABLE refunds (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id         BIGINT       NOT NULL REFERENCES orders(id),
    payment_id       BIGINT       REFERENCES payments(id),  -- 원 결제(환불 대상). 없을 수 있음
    amount           INTEGER      NOT NULL,                 -- 환불 예정액(결제액 - 수수료)
    fee              INTEGER      NOT NULL,                 -- 취소 수수료
    reason           VARCHAR(100),                          -- 취소 사유
    pg_refund_tid    VARCHAR(100),                          -- PG 환불 거래 ID
    idempotency_key  VARCHAR(80)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);
ALTER TABLE refunds ADD CONSTRAINT uq_refunds_idem UNIQUE (idempotency_key);
CREATE INDEX ix_refunds_order ON refunds (order_id);
COMMENT ON TABLE refunds IS '환불 기록. idempotency_key UNIQUE + 주문 조건부 전이(PAID→CANCELLED→REFUNDED)로 이중 환불 차단(ADR-006)';
