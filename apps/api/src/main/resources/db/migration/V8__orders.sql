-- 주문 헤더 + 라인(가격 스냅샷). 좌석 선점(hold)을 결제 대상으로 승격.
CREATE TABLE orders (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       BIGINT      NOT NULL REFERENCES events(id),
    user_id        BIGINT      NOT NULL,  -- 인증 JWT userId(신뢰). FK 미설정(결합 완화)
    hold_id        BIGINT      NOT NULL,  -- 근거 좌석 선점(FK 미설정, 결합 완화)
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount         INTEGER     NOT NULL,
    payment_method VARCHAR(10),           -- 결제 시 채움(S05 BE-2)
    expires_at     TIMESTAMP   NOT NULL,  -- 결제 제한시각(= hold 잔여 TTL)
    paid_at        TIMESTAMP,
    created_at     TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX ix_orders_status_exp ON orders (status, expires_at);
CREATE INDEX ix_orders_user ON orders (user_id);
COMMENT ON TABLE orders IS '주문 헤더. 상태전이 조건부 UPDATE(ADR-006). PAID 시 좌석 SOLD/hold CONVERTED';

CREATE TABLE order_items (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id BIGINT      NOT NULL REFERENCES orders(id),
    seat_id  BIGINT      NOT NULL REFERENCES seats(id),
    grade    VARCHAR(10) NOT NULL,
    price    INTEGER     NOT NULL  -- 주문 시점 가격 스냅샷(ADR-004)
);
CREATE INDEX ix_order_items_order ON order_items (order_id);
ALTER TABLE order_items ADD CONSTRAINT uq_order_items_seat UNIQUE (order_id, seat_id);
COMMENT ON TABLE order_items IS '주문 좌석 라인. 등급·가격을 주문 시점 스냅샷으로 보관';
