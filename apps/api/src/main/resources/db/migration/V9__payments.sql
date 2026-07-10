-- 개별 결제 시도. 한 주문에 여러 시도(FAILED 다수 + 최종 APPROVED 1). 멱등키로 중복 차단.
CREATE TABLE payments (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id         BIGINT      NOT NULL REFERENCES orders(id),
    method           VARCHAR(10) NOT NULL,  -- card/easy/vbank
    provider         VARCHAR(20),           -- easy 결제 provider
    status           VARCHAR(20) NOT NULL DEFAULT 'READY',  -- PaymentStatus
    amount           INTEGER     NOT NULL,
    idempotency_key  VARCHAR(80) NOT NULL,
    pg_tid           VARCHAR(100),          -- PG 거래 ID(웹훅 멱등 대조)
    vbank_account    VARCHAR(50),           -- 무통장 가상계좌(S05 BE-3)
    deposit_deadline TIMESTAMP,             -- 입금 기한(무통장)
    approved_at      TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL DEFAULT now()
);
ALTER TABLE payments ADD CONSTRAINT uq_payments_idem UNIQUE (idempotency_key);
CREATE INDEX ix_payments_order ON payments (order_id);
CREATE INDEX ix_payments_pg_tid ON payments (pg_tid);
COMMENT ON TABLE payments IS '결제 시도. idempotency_key UNIQUE + 조건부 주문전이로 이중 결제 차단(ADR-006)';
