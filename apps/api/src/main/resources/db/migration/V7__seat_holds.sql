-- 좌석 선점(HOLD). TTL 만료/해제/결제확정으로 상태 전이.
CREATE TABLE seat_holds (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events(id),
    user_id    BIGINT      NOT NULL,  -- 인증 JWT의 userId(신뢰). FK 미설정(결합 완화)

    status     VARCHAR(20) NOT NULL DEFAULT 'HELD',
    expires_at TIMESTAMP   NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX ix_holds_status_exp ON seat_holds (status, expires_at);
CREATE INDEX ix_holds_user_event ON seat_holds (user_id, event_id);
COMMENT ON TABLE seat_holds IS '좌석 선점. 만료 sweep가 HELD/expired→EXPIRED + 좌석 복구';

CREATE TABLE seat_hold_items (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hold_id BIGINT NOT NULL REFERENCES seat_holds(id),
    seat_id BIGINT NOT NULL REFERENCES seats(id)
);
CREATE INDEX ix_hold_items_hold ON seat_hold_items (hold_id);
